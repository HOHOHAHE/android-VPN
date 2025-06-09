package com.example.vpntest.core

import android.content.Context
import android.util.Log
import com.example.vpntest.network.VpnNetworkManager
import com.example.vpntest.migration.MigrationFlags
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Main packet processor that coordinates packet processing through protocol handlers
 *
 * @Deprecated("遷移至 HEV Tunnel 架構")
 * 這是舊版本的封包處理器，在遷移到 HEV Socks5 Tunnel 後將被逐步淘汰。
 * 目前保留作為向後相容和備用方案使用。
 *
 * 相關遷移：
 * - 新架構: HevTunnelManager 和相關組件
 * - 控制標誌: MigrationFlags.shouldUseLegacyProcessor()
 * - 預計移除版本: v2.0.0
 */
@Deprecated("Use HEV Tunnel architecture instead", ReplaceWith("HevTunnelManager"))
class VpnPacketProcessor(
    private val context: Context,
    private val networkManager: VpnNetworkManager,
    private val sessionManager: DefaultSessionManager,
    private val connectionRouter: ConnectionRouter
) : PacketProcessor {
    
    companion object {
        private const val TAG = "VpnPacketProcessor"
        private const val PACKET_BUFFER_SIZE = 32767
        private const val IPV4_HEADER_SIZE = 20
    }
    
    private val protocolHandlers = mutableMapOf<Int, ProtocolHandler>()
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var _isRunning = false
    override val isRunning: Boolean get() = _isRunning
    
    private var inputChannel: FileChannel? = null
    private var outputChannel: FileChannel? = null
    private var processingJob: Job? = null
    
    override fun start() {
        if (_isRunning) {
            Log.w(TAG, "Packet processor already running")
            return
        }
        
        // 檢查遷移標誌，決定是否使用舊處理器
        if (!MigrationFlags.shouldUseLegacyProcessor()) {
            Log.i(TAG, "Legacy packet processor disabled by migration flags")
            Log.i(TAG, "Current migration info: ${MigrationFlags.getMigrationInfo()}")
            return
        }
        
        Log.w(TAG, "使用舊版封包處理器 - 此組件已被標記為廢棄，建議遷移至 HEV Tunnel")
        
        try {
            val vpnInterface = networkManager.getVpnInterface()
            if (vpnInterface == null) {
                Log.e(TAG, "VPN interface not available")
                return
            }
            
            inputChannel = FileInputStream(vpnInterface.fileDescriptor).channel
            outputChannel = FileOutputStream(vpnInterface.fileDescriptor).channel
            
            _isRunning = true
            
            processingJob = processingScope.launch {
                processPackets()
            }
            
            Log.i(TAG, "VPN packet processor started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start packet processor", e)
            stop()
        }
    }
    
    override fun stop() {
        if (!_isRunning) return
        
        _isRunning = false
        processingJob?.cancel()
        processingScope.cancel()
        
        inputChannel = null
        outputChannel = null
        
        Log.i(TAG, "VPN packet processor stopped")
    }
    
    override suspend fun processPacket(packet: ByteBuffer) {
        if (!_isRunning) return
        
        try {
            sessionManager.incrementPacketsProcessed()
            
            val packetInfo = parseIPPacket(packet) ?: return
            
            // Route packet to appropriate protocol handler
            val handler = protocolHandlers[packetInfo.protocol]
            if (handler != null) {
                val session = sessionManager.getSession(packetInfo.connectionKey)
                handler.handlePacket(packet, session)
            } else {
                Log.v(TAG, "No handler for protocol ${packetInfo.protocol}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet", e)
            sessionManager.incrementErrors()
        }
    }
    
    /**
     * Register a protocol handler
     */
    fun registerHandler(handler: ProtocolHandler) {
        protocolHandlers[handler.protocol] = handler
        Log.d(TAG, "Registered handler for protocol ${handler.protocol}")
    }
    
    /**
     * Unregister a protocol handler
     */
    fun unregisterHandler(protocol: Int) {
        protocolHandlers.remove(protocol)
        Log.d(TAG, "Unregistered handler for protocol $protocol")
    }
    
    /**
     * Send packet back through TUN interface
     */
    suspend fun sendPacket(packet: ByteBuffer) {
        try {
            outputChannel?.write(packet)
            sessionManager.addBytesTransferred(packet.remaining().toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet through TUN", e)
            sessionManager.incrementErrors()
        }
    }
    
    /**
     * Get packet processor statistics
     */
    fun getStats(): String {
        val sessionStats = sessionManager.getStats()
        return buildString {
            appendLine("=== Packet Processor Stats ===")
            appendLine("Running: $_isRunning")
            appendLine("Protocol Handlers: ${protocolHandlers.size}")
            protocolHandlers.forEach { (protocol, handler) ->
                val protocolName = when (protocol) {
                    1 -> "ICMP"
                    6 -> "TCP"
                    17 -> "UDP"
                    else -> "Protocol $protocol"
                }
                appendLine("  $protocolName: ${handler::class.simpleName}")
            }
            appendLine("Session Stats:")
            appendLine("  Packets processed: ${sessionStats.packetsProcessed}")
            appendLine("  Bytes transferred: ${sessionStats.bytesTransferred}")
            appendLine("  Active sessions: ${sessionStats.activeSessions}")
            appendLine("  Errors: ${sessionStats.errorsCount}")
        }
    }
    
    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val buffer = ByteBuffer.allocate(PACKET_BUFFER_SIZE)
        
        while (_isRunning) {
            try {
                val inputCh = inputChannel
                if (inputCh == null) {
                    Log.w(TAG, "Input channel is null, stopping packet processing")
                    break
                }
                
                buffer.clear()
                val bytesRead = inputCh.read(buffer)
                
                if (bytesRead > 0) {
                    buffer.flip()
                    processPacket(buffer)
                }
                
                // Small delay to prevent 100% CPU usage
                if (bytesRead <= 0) {
                    delay(1)
                }
                
            } catch (e: Exception) {
                if (_isRunning) {
                    Log.e(TAG, "Error in packet processing loop", e)
                    sessionManager.incrementErrors()
                }
            }
        }
    }
    
    private fun parseIPPacket(packet: ByteBuffer): PacketInfo? {
        if (packet.remaining() < 1) return null
        
        val originalPosition = packet.position()
        
        try {
            // Parse IP header
            val versionAndHeaderLength = packet.get().toInt() and 0xFF
            val version = (versionAndHeaderLength shr 4) and 0xF
            
            // Handle IPv6 packets by silently dropping them
            if (version == 6) {
                Log.v(TAG, "IPv6 packet dropped (as expected with IPv6 blocking)")
                return null
            }
            
            // Ensure we have enough data for IPv4 header
            if (packet.remaining() < IPV4_HEADER_SIZE - 1) {
                Log.v(TAG, "Packet too short for IPv4 header")
                return null
            }
            
            val headerLength = (versionAndHeaderLength and 0xF) * 4
            
            if (version != 4 || headerLength < IPV4_HEADER_SIZE) {
                Log.v(TAG, "Invalid IPv4 packet: version=$version, headerLength=$headerLength")
                return null
            }
            
            packet.position(originalPosition + 9)
            val protocol = packet.get().toInt() and 0xFF
            
            packet.position(originalPosition + 12)
            val srcIP = ByteArray(4)
            packet.get(srcIP)
            val sourceAddress = java.net.InetAddress.getByAddress(srcIP)
            
            val dstIP = ByteArray(4)
            packet.get(dstIP)
            val destAddress = java.net.InetAddress.getByAddress(dstIP)
            
            // Validate VPN traffic
            val sourceIP = sourceAddress.hostAddress
            if (!sourceIP!!.startsWith("10.0.0.")) {
                Log.d(TAG, "Non-VPN traffic detected from $sourceIP")
            }
            
            // Extract port information for connection key
            var sourcePort = 0
            var destPort = 0
            
            if (protocol == 6 || protocol == 17) { // TCP or UDP
                val transportHeaderStart = originalPosition + headerLength
                if (packet.remaining() >= transportHeaderStart + 4) {
                    packet.position(transportHeaderStart)
                    sourcePort = packet.short.toInt() and 0xFFFF
                    destPort = packet.short.toInt() and 0xFFFF
                }
            }
            
            // Reset packet position for handler processing
            packet.position(originalPosition)
            
            val connectionKey = "${sourceAddress.hostAddress}:$sourcePort->${destAddress.hostAddress}:$destPort"
            
            return PacketInfo(
                protocol = protocol,
                sourceAddress = sourceAddress,
                sourcePort = sourcePort,
                destAddress = destAddress,
                destPort = destPort,
                connectionKey = connectionKey,
                headerLength = headerLength
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing IP packet", e)
            return null
        }
    }
    
    /**
     * Information extracted from IP packet
     */
    data class PacketInfo(
        val protocol: Int,
        val sourceAddress: java.net.InetAddress,
        val sourcePort: Int,
        val destAddress: java.net.InetAddress,
        val destPort: Int,
        val connectionKey: String,
        val headerLength: Int
    )
}
