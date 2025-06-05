package com.example.vpntest.core

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of ConnectionRouter
 * Handles routing decisions for VPN connections
 */
class DefaultConnectionRouter : ConnectionRouter {
    
    companion object {
        private const val TAG = "ConnectionRouter"
    }
    
    private val proxies = ConcurrentHashMap<String, ProxyConnector>()
    private val rules = ConcurrentHashMap<String, RoutingRule>()
    private var defaultProxy: ProxyConnector? = null
    
    init {
        // Add default rules
        addDefaultRules()
    }
    
    override fun shouldProxy(targetHost: String, targetPort: Int): Boolean {
        // Check rules in priority order
        val sortedRules = rules.values.sortedByDescending { it.priority }
        
        for (rule in sortedRules) {
            if (matchesRule(rule, targetHost, targetPort)) {
                return when (rule.action) {
                    RoutingAction.PROXY -> true
                    RoutingAction.DIRECT -> false
                    RoutingAction.BLOCK -> false
                }
            }
        }
        
        // Default: proxy everything
        return true
    }
    
    override fun getProxyForConnection(targetHost: String, targetPort: Int): ProxyConnector? {
        if (!shouldProxy(targetHost, targetPort)) {
            return null
        }
        
        // Check for specific proxy rules
        val sortedRules = rules.values.sortedByDescending { it.priority }
        
        for (rule in sortedRules) {
            if (rule.action == RoutingAction.PROXY && 
                matchesRule(rule, targetHost, targetPort) && 
                rule.proxyType != null) {
                
                val proxy = proxies[rule.proxyType]
                if (proxy != null && proxy.isHealthy()) {
                    Log.d(TAG, "Using proxy ${rule.proxyType} for $targetHost:$targetPort (rule: ${rule.id})")
                    return proxy
                }
            }
        }
        
        // Return default proxy
        return defaultProxy?.takeIf { it.isHealthy() }
    }
    
    override fun addRule(rule: RoutingRule) {
        rules[rule.id] = rule
        Log.d(TAG, "Added routing rule: ${rule.id} - ${rule.pattern} -> ${rule.action}")
    }
    
    override fun removeRule(ruleId: String) {
        rules.remove(ruleId)
        Log.d(TAG, "Removed routing rule: $ruleId")
    }
    
    /**
     * Register a proxy connector
     */
    fun registerProxy(proxyType: String, proxy: ProxyConnector) {
        proxies[proxyType] = proxy
        Log.d(TAG, "Registered proxy: $proxyType")
    }
    
    /**
     * Unregister a proxy connector
     */
    fun unregisterProxy(proxyType: String) {
        proxies.remove(proxyType)
        Log.d(TAG, "Unregistered proxy: $proxyType")
    }
    
    /**
     * Set the default proxy
     */
    fun setDefaultProxy(proxy: ProxyConnector) {
        defaultProxy = proxy
        Log.d(TAG, "Set default proxy: ${proxy.proxyType}")
    }
    
    /**
     * Get routing information for debugging
     */
    fun getRoutingInfo(): String {
        return buildString {
            appendLine("=== Connection Router Info ===")
            appendLine("Registered Proxies: ${proxies.size}")
            proxies.forEach { (type, proxy) ->
                appendLine("  $type: ${proxy::class.simpleName} (healthy: ${proxy.isHealthy()})")
            }
            
            appendLine("Default Proxy: ${defaultProxy?.proxyType ?: "None"}")
            
            appendLine("Routing Rules: ${rules.size}")
            rules.values.sortedByDescending { it.priority }.forEach { rule ->
                appendLine("  [${rule.priority}] ${rule.id}: ${rule.pattern} -> ${rule.action} ${rule.proxyType ?: ""}")
            }
        }
    }
    
    private fun addDefaultRules() {
        // DNS traffic - use direct connection to avoid loops
        addRule(RoutingRule(
            id = "dns_direct",
            pattern = "*:53",
            action = RoutingAction.DIRECT,
            priority = 100
        ))
        
        // Local network traffic - direct connection
        addRule(RoutingRule(
            id = "local_direct",
            pattern = "127.*:*",
            action = RoutingAction.DIRECT,
            priority = 90
        ))
        
        addRule(RoutingRule(
            id = "local_10_direct",
            pattern = "10.*:*",
            action = RoutingAction.DIRECT,
            priority = 90
        ))
        
        addRule(RoutingRule(
            id = "local_192_direct",
            pattern = "192.168.*:*",
            action = RoutingAction.DIRECT,
            priority = 90
        ))
        
        // HTTPS traffic - use proxy
        addRule(RoutingRule(
            id = "https_proxy",
            pattern = "*:443",
            action = RoutingAction.PROXY,
            proxyType = "SOCKS5",
            priority = 50
        ))
        
        // HTTP traffic - use proxy
        addRule(RoutingRule(
            id = "http_proxy",
            pattern = "*:80",
            action = RoutingAction.PROXY,
            proxyType = "SOCKS5",
            priority = 50
        ))
        
        // Default rule - proxy everything else
        addRule(RoutingRule(
            id = "default_proxy",
            pattern = "*:*",
            action = RoutingAction.PROXY,
            proxyType = "SOCKS5",
            priority = 0
        ))
    }
    
    private fun matchesRule(rule: RoutingRule, targetHost: String, targetPort: Int): Boolean {
        return try {
            val pattern = rule.pattern
            
            // Simple pattern matching
            if (pattern == "*:*") {
                return true
            }
            
            val parts = pattern.split(":")
            if (parts.size != 2) {
                return false
            }
            
            val hostPattern = parts[0]
            val portPattern = parts[1]
            
            // Check port
            val portMatches = when {
                portPattern == "*" -> true
                portPattern.isDigitsOnly() -> portPattern.toInt() == targetPort
                else -> false
            }
            
            if (!portMatches) {
                return false
            }
            
            // Check host
            val hostMatches = when {
                hostPattern == "*" -> true
                hostPattern.endsWith("*") -> {
                    val prefix = hostPattern.dropLast(1)
                    targetHost.startsWith(prefix)
                }
                hostPattern.startsWith("*") -> {
                    val suffix = hostPattern.drop(1)
                    targetHost.endsWith(suffix)
                }
                else -> hostPattern == targetHost
            }
            
            hostMatches
            
        } catch (e: Exception) {
            Log.w(TAG, "Error matching rule pattern: ${rule.pattern}", e)
            false
        }
    }
    
    private fun String.isDigitsOnly(): Boolean {
        return this.all { it.isDigit() }
    }
    
    /**
     * Test a connection against routing rules
     */
    fun testConnection(targetHost: String, targetPort: Int): String {
        val shouldUseProxy = shouldProxy(targetHost, targetPort)
        val proxy = getProxyForConnection(targetHost, targetPort)
        
        return buildString {
            appendLine("=== Connection Test: $targetHost:$targetPort ===")
            appendLine("Should proxy: $shouldUseProxy")
            appendLine("Selected proxy: ${proxy?.proxyType ?: "Direct connection"}")
            
            // Show matching rules
            val matchingRules = rules.values.filter { matchesRule(it, targetHost, targetPort) }
                .sortedByDescending { it.priority }
            
            appendLine("Matching rules:")
            matchingRules.forEach { rule ->
                appendLine("  [${rule.priority}] ${rule.id}: ${rule.pattern} -> ${rule.action} ${rule.proxyType ?: ""}")
            }
        }
    }
}
