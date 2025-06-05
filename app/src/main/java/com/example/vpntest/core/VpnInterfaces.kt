package com.example.vpntest.core

import java.nio.ByteBuffer
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for packet processing components
 */
interface PacketProcessor {
    suspend fun processPacket(packet: ByteBuffer)
    fun start()
    fun stop()
    val isRunning: Boolean
}

/**
 * Interface for managing VPN sessions
 */
interface SessionManager {
    fun createSession(session: VpnSession): Boolean
    fun getSession(connectionKey: String): VpnSession?
    fun removeSession(connectionKey: String): Boolean
    fun getAllSessions(): List<VpnSession>
    fun getStats(): SessionStats
    fun cleanup()
}

/**
 * Interface for protocol-specific handlers
 */
interface ProtocolHandler {
    val protocol: Int // IP protocol number (6=TCP, 17=UDP, 1=ICMP)
    suspend fun handlePacket(packet: ByteBuffer, session: VpnSession? = null): Boolean
    fun isSupported(protocolNumber: Int): Boolean
}

/**
 * Interface for proxy implementations
 */
interface ProxyConnector {
    val proxyType: String
    suspend fun connect(targetHost: String, targetPort: Int): ProxyConnection?
    fun isHealthy(): Boolean
    fun getStats(): ProxyStats
}

/**
 * Represents an active proxy connection
 */
interface ProxyConnection {
    val isConnected: Boolean
    suspend fun sendData(data: ByteArray): Boolean
    suspend fun receiveData(): ByteArray?
    fun close()
}

/**
 * Proxy statistics
 */
data class ProxyStats(
    val connectionCount: Int,
    val bytesTransferred: Long,
    val errorCount: Int,
    val avgResponseTime: Long
)

/**
 * Interface for network management
 */
interface NetworkManager {
    fun setupVpnInterface(): Boolean
    fun teardownVpnInterface()
    fun getUnderlyingNetwork(): android.net.Network?
    val vpnStatus: StateFlow<VpnStatus>
}

/**
 * VPN connection status
 */
enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

/**
 * Interface for routing decisions
 */
interface ConnectionRouter {
    fun shouldProxy(targetHost: String, targetPort: Int): Boolean
    fun getProxyForConnection(targetHost: String, targetPort: Int): ProxyConnector?
    fun addRule(rule: RoutingRule)
    fun removeRule(ruleId: String)
}

/**
 * Routing rule for connection decisions
 */
data class RoutingRule(
    val id: String,
    val pattern: String, // IP pattern, domain pattern, etc.
    val action: RoutingAction,
    val proxyType: String? = null,
    val priority: Int = 0
)

enum class RoutingAction {
    PROXY,    // Route through proxy
    DIRECT,   // Direct connection
    BLOCK     // Block connection
}
