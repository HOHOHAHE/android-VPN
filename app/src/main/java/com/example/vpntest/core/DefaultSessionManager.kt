package com.example.vpntest.core

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Default implementation of SessionManager
 * Manages active VPN sessions with automatic cleanup and statistics
 */
class DefaultSessionManager(
    private val sessionTimeoutMs: Long = 300_000, // 5 minutes timeout
    private val cleanupIntervalMs: Long = 60_000   // Cleanup every minute
) : SessionManager {
    
    companion object {
        private const val TAG = "SessionManager"
    }
    
    private val sessions = ConcurrentHashMap<String, VpnSession>()
    private val sessionIdCounter = AtomicInteger(0)
    private val totalSessionsCreated = AtomicInteger(0)
    private val bytesTransferred = AtomicLong(0)
    private val packetsProcessed = AtomicLong(0)
    private val errorsCount = AtomicLong(0)
    
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cleanupJob: Job? = null
    
    init {
        startCleanupTask()
    }
    
    override fun createSession(session: VpnSession): Boolean {
        return try {
            val sessionWithId = session.copy(
                sessionId = sessionIdCounter.incrementAndGet()
            )
            
            sessions[session.connectionKey] = sessionWithId
            totalSessionsCreated.incrementAndGet()
            
            Log.d(TAG, "Created session ${sessionWithId.sessionId}: ${session.connectionString()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session: ${session.connectionString()}", e)
            errorsCount.incrementAndGet()
            false
        }
    }
    
    override fun getSession(connectionKey: String): VpnSession? {
        return sessions[connectionKey]
    }
    
    override fun removeSession(connectionKey: String): Boolean {
        val session = sessions.remove(connectionKey)
        return if (session != null) {
            Log.d(TAG, "Removed session ${session.sessionId}: ${session.connectionString()}")
            
            // Cancel relay job and close socket
            session.relayJob?.cancel()
            try {
                session.socket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket for session ${session.sessionId}", e)
            }
            
            true
        } else {
            false
        }
    }
    
    override fun getAllSessions(): List<VpnSession> {
        return sessions.values.toList()
    }
    
    override fun getStats(): SessionStats {
        return SessionStats(
            totalSessions = totalSessionsCreated.get(),
            activeSessions = sessions.size,
            bytesTransferred = bytesTransferred.get(),
            packetsProcessed = packetsProcessed.get(),
            errorsCount = errorsCount.get()
        )
    }
    
    override fun cleanup() {
        Log.i(TAG, "Cleaning up SessionManager")
        
        cleanupJob?.cancel()
        cleanupScope.cancel()
        
        // Close all active sessions
        val activeSessions = sessions.values.toList()
        activeSessions.forEach { session ->
            removeSession(session.connectionKey)
        }
        
        sessions.clear()
        Log.i(TAG, "SessionManager cleanup completed")
    }
    
    /**
     * Update statistics - called by packet processors
     */
    fun incrementPacketsProcessed() {
        packetsProcessed.incrementAndGet()
    }
    
    fun addBytesTransferred(bytes: Long) {
        bytesTransferred.addAndGet(bytes)
    }
    
    fun incrementErrors() {
        errorsCount.incrementAndGet()
    }
    
    /**
     * Update session state
     */
    fun updateSession(connectionKey: String, updater: (VpnSession) -> VpnSession): Boolean {
        val currentSession = sessions[connectionKey] ?: return false
        val updatedSession = updater(currentSession)
        sessions[connectionKey] = updatedSession
        return true
    }
    
    private fun startCleanupTask() {
        cleanupJob = cleanupScope.launch {
            while (isActive) {
                try {
                    cleanupExpiredSessions()
                    delay(cleanupIntervalMs)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in cleanup task", e)
                    }
                }
            }
        }
    }
    
    private fun cleanupExpiredSessions() {
        val currentTime = System.currentTimeMillis()
        val expiredSessions = sessions.values.filter { session ->
            (currentTime - session.createdTime) > sessionTimeoutMs
        }
        
        if (expiredSessions.isNotEmpty()) {
            Log.d(TAG, "Cleaning up ${expiredSessions.size} expired sessions")
            expiredSessions.forEach { session ->
                removeSession(session.connectionKey)
            }
        }
    }
    
    /**
     * Get sessions by protocol
     */
    fun getSessionsByProtocol(protocol: Int): List<VpnSession> {
        return sessions.values.filter { it.protocol == protocol }
    }
    
    /**
     * Get session statistics summary
     */
    fun getDetailedStats(): String {
        val stats = getStats()
        val protocolBreakdown = sessions.values.groupBy { it.protocol }
            .mapValues { it.value.size }
        
        return buildString {
            appendLine("=== Session Manager Stats ===")
            appendLine("Total sessions created: ${stats.totalSessions}")
            appendLine("Active sessions: ${stats.activeSessions}")
            appendLine("Bytes transferred: ${stats.bytesTransferred}")
            appendLine("Packets processed: ${stats.packetsProcessed}")
            appendLine("Errors: ${stats.errorsCount}")
            appendLine("Protocol breakdown:")
            protocolBreakdown.forEach { (protocol, count) ->
                val protocolName = when (protocol) {
                    1 -> "ICMP"
                    6 -> "TCP"
                    17 -> "UDP"
                    else -> "Protocol $protocol"
                }
                appendLine("  $protocolName: $count sessions")
            }
        }
    }
}
