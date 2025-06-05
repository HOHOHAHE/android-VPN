package com.example.vpntest.core

import kotlinx.coroutines.Job
import java.net.InetAddress
import java.net.Socket

/**
 * TCP connection states following RFC 793
 */
enum class TCPState {
    SYN_SENT,     // SOCKS connection in progress
    ESTABLISHED,  // Ready for data transfer
    FIN_WAIT,     // Closing connection
    CLOSED        // Connection terminated
}

/**
 * Represents an active VPN session/connection
 */
data class VpnSession(
    val sessionId: Int,
    val connectionKey: String,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destAddress: InetAddress,
    val destPort: Int,
    val protocol: Int, // 6=TCP, 17=UDP, 1=ICMP
    val socket: Socket? = null,
    val relayJob: Job? = null,
    var localSequenceNumber: Long = 0L,
    var remoteSequenceNumber: Long = 0L,
    var lastAckNumber: Long = 0L,
    var clientSequenceNumber: Long = 0L,
    var state: TCPState = TCPState.SYN_SENT,
    @Volatile var isEstablished: Boolean = false,
    val createdTime: Long = System.currentTimeMillis()
) {
    fun connectionString(): String = 
        "${sourceAddress.hostAddress}:$sourcePort->${destAddress.hostAddress}:$destPort"
}

/**
 * Session statistics for monitoring
 */
data class SessionStats(
    val totalSessions: Int,
    val activeSessions: Int,
    val bytesTransferred: Long,
    val packetsProcessed: Long,
    val errorsCount: Long
)
