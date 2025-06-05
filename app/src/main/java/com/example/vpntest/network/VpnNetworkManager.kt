package com.example.vpntest.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.vpntest.core.NetworkManager
import com.example.vpntest.core.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages VPN network interface and underlying network detection
 */
class VpnNetworkManager(
    private val context: Context,
    private val vpnService: VpnService
) : NetworkManager {
    
    companion object {
        private const val TAG = "VpnNetworkManager"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val DNS_SERVER_1 = "8.8.8.8"
        private const val DNS_SERVER_2 = "8.8.4.4"
        private const val MTU = 1500
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private val connectivityManager by lazy { 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
    }
    
    private val _vpnStatus = MutableStateFlow(VpnStatus.DISCONNECTED)
    override val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()
    
    override fun setupVpnInterface(): Boolean {
        return try {
            _vpnStatus.value = VpnStatus.CONNECTING
            
            val builder = createVpnBuilder()
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                _vpnStatus.value = VpnStatus.CONNECTED
                Log.i(TAG, "VPN interface established successfully")
                true
            } else {
                _vpnStatus.value = VpnStatus.ERROR
                Log.e(TAG, "Failed to establish VPN interface")
                false
            }
        } catch (e: Exception) {
            _vpnStatus.value = VpnStatus.ERROR
            Log.e(TAG, "Error setting up VPN interface", e)
            false
        }
    }
    
    override fun teardownVpnInterface() {
        try {
            _vpnStatus.value = VpnStatus.DISCONNECTING
            
            vpnInterface?.close()
            vpnInterface = null
            
            _vpnStatus.value = VpnStatus.DISCONNECTED
            Log.i(TAG, "VPN interface torn down")
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down VPN interface", e)
            _vpnStatus.value = VpnStatus.ERROR
        }
    }
    
    override fun getUnderlyingNetwork(): Network? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                findUnderlyingNetwork()
            } else {
                Log.w(TAG, "Underlying network detection not available on API < 23")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding underlying network", e)
            null
        }
    }
    
    /**
     * Get the VPN interface file descriptor for packet I/O
     */
    fun getVpnInterface(): ParcelFileDescriptor? = vpnInterface
    
    /**
     * Check if VPN interface is active
     */
    fun isVpnActive(): Boolean = vpnInterface != null && _vpnStatus.value == VpnStatus.CONNECTED
    
    private fun createVpnBuilder(): VpnService.Builder {
        val builder = vpnService.Builder()
            .setMtu(MTU)
            .addAddress(VPN_ADDRESS, 24)
            .addRoute(VPN_ROUTE, 0)
            .addDnsServer(DNS_SERVER_1)
            .addDnsServer(DNS_SERVER_2)
            .setSession("LeafVPN")
            .setBlocking(false) // Non-blocking mode for better performance
        
        // Exclude our own app from VPN routing to prevent loops
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addDisallowedApplication(context.packageName)
                Log.d(TAG, "Excluded self from VPN routing")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to exclude self from VPN", e)
        }
        
        // Block IPv6 traffic by routing to blackhole
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addRoute("::", 0)
                Log.d(TAG, "Added IPv6 blocking route")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add IPv6 blocking route", e)
        }
        
        return builder
    }
    
    private fun findUnderlyingNetwork(): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        
        val networks = connectivityManager.allNetworks
        Log.d(TAG, "Available networks: ${networks.size}")
        
        // First pass: Find connected non-VPN networks
        for (network in networks) {
            try {
                val networkInfo = connectivityManager.getNetworkInfo(network)
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                
                Log.d(TAG, "Network: $network, connected: ${networkInfo?.isConnected}, " +
                      "type: ${networkInfo?.typeName}, capabilities: $networkCapabilities")
                
                if (networkInfo != null && networkInfo.isConnected && 
                    networkInfo.type != ConnectivityManager.TYPE_VPN) {
                    
                    // Additional check using NetworkCapabilities
                    if (networkCapabilities != null && 
                        !networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.d(TAG, "Found underlying network: ${networkInfo.typeName}")
                        return network
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking network $network", e)
            }
        }
        
        // Second pass: Find any internet-capable non-VPN network
        for (network in networks) {
            try {
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                if (networkCapabilities != null && 
                    networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    Log.d(TAG, "Found internet-capable non-VPN network: $network")
                    return network
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking network capabilities for $network", e)
            }
        }
        
        Log.w(TAG, "No underlying network found")
        return null
    }
    
    /**
     * Get detailed network information for debugging
     */
    fun getNetworkInfo(): String {
        return buildString {
            appendLine("=== Network Information ===")
            appendLine("VPN Status: ${_vpnStatus.value}")
            appendLine("VPN Interface Active: ${isVpnActive()}")
            
            val underlyingNetwork = getUnderlyingNetwork()
            appendLine("Underlying Network: $underlyingNetwork")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networks = connectivityManager.allNetworks
                appendLine("Available Networks: ${networks.size}")
                
                networks.forEachIndexed { index, network ->
                    try {
                        val networkInfo = connectivityManager.getNetworkInfo(network)
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                        
                        appendLine("Network $index: $network")
                        appendLine("  Type: ${networkInfo?.typeName}")
                        appendLine("  Connected: ${networkInfo?.isConnected}")
                        appendLine("  Capabilities: $capabilities")
                    } catch (e: Exception) {
                        appendLine("Network $index: Error - ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Test connectivity through underlying network
     */
    fun testUnderlyingConnectivity(): Boolean {
        val underlyingNetwork = getUnderlyingNetwork() ?: return false
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Simple connectivity test
                val socket = java.net.Socket()
                underlyingNetwork.bindSocket(socket)
                socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 5000)
                socket.close()
                Log.d(TAG, "Underlying network connectivity test passed")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Underlying network connectivity test failed", e)
            false
        }
    }
}
