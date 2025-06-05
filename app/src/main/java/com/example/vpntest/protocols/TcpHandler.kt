package com.example.vpntest.protocols

import android.content.Context
import android.util.Log
import com.example.vpntest.core.*
import com.example.vpntest.network.VpnNetworkManager
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP protocol handler that manages TCP connections through proxies
 */
class TcpHandler(
    private val context: Context,
    private val networkManager: VpnNetworkManager,
    private val sessionManager: DefaultSessionManager,
    private val connectionRouter: DefaultConnectionRouter,
    private val packetProcessor: VpnPacketProcessor
) : ProtocolHandler {
    
    companion object {
        private const val TAG = "TcpHandler"
        private const val TCP_HEADER_SIZE = 20
    }
    
    override val protocol: Int = 6 // TCP
    
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionIdCounter = AtomicInteger(0)
    
    override suspend fun handlePacket(packet: ByteBuffer, session: VpnSession?): Boolean {
        return try {
            val tcpInfo = parseTcpPacket(packet) ?: return false
            
            Log.d(TAG, "TCP packet: ${tcpInfo.connectionKey}, flags: 0x${tcpInfo.flags.toString(16).padStart(2, '0')} " +
                  "(${getTCPFlagsString(tcpInfo.flags)}), seq: ${tcpInfo.sequenceNumber}, ack: ${tcpInfo.ackNumber}, payload: ${tcpInfo.payloadLength} bytes")
            
            // Handle based on TCP flags
            when {
                (tcpInfo.flags and 0x02) != 0 -> { // SYN flag
                    handleTcpSyn(tcpInfo)
                }
                (tcpInfo.flags and 0x10) != 0 && (tcpInfo.flags and 0x02) == 0 -> { // ACK flag (not SYN-ACK)
                    handleTcpAck(tcpInfo, session)
                }
                (tcpInfo.flags and 0x01) != 0 -> { // FIN flag
                    handleTcpFin(tcpInfo, session)
                }
                (tcpInfo.flags and 0x04) != 0 -> { // RST flag
                    handleTcpReset(tcpInfo, session)
                }
                tcpInfo.payloadLength > 0 -> { // Data packet
                    handleTcpData(tcpInfo, session, packet)
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling TCP packet", e)
            false
        }
    }
    
    override fun isSupported(protocolNumber: Int): Boolean = protocolNumber == 6
    
    private fun parseTcpPacket(packet: ByteBuffer): TcpPacketInfo? {
        return try {
            val originalPosition = packet.position()
            
            // Skip to IP protocol field to confirm it's TCP
            packet.position(originalPosition + 9)
            val protocol = packet.get().toInt() and 0xFF
            if (protocol != 6) return null
            
            // Extract IP addresses
            packet.position(originalPosition + 12)
            val srcIP = ByteArray(4)
            packet.get(srcIP)
            val sourceAddress = InetAddress.getByAddress(srcIP)
            
            val dstIP = ByteArray(4)
            packet.get(dstIP)
            val destAddress = InetAddress.getByAddress(dstIP)
            
            // Extract IP header length
            packet.position(originalPosition)
            val versionAndHeaderLength = packet.get().toInt() and 0xFF
            val ipHeaderLength = (versionAndHeaderLength and 0xF) * 4
            
            // Parse TCP header
            val tcpHeaderStart = originalPosition + ipHeaderLength
            packet.position(tcpHeaderStart)
            
            val sourcePort = packet.short.toInt() and 0xFFFF
            val destPort = packet.short.toInt() and 0xFFFF
            val sequenceNumber = packet.int.toLong() and 0xFFFFFFFFL
            val ackNumber = packet.int.toLong() and 0xFFFFFFFFL
            
            val headerLengthAndFlags = packet.short.toInt() and 0xFFFF
            val tcpHeaderLength = ((headerLengthAndFlags shr 12) and 0xF) * 4
            val flags = headerLengthAndFlags and 0xFF
            
            val payloadStart = tcpHeaderStart + tcpHeaderLength
            val payloadLength = packet.limit() - payloadStart
            
            val connectionKey = "${sourceAddress.hostAddress}:$sourcePort->${destAddress.hostAddress}:$destPort"
            
            TcpPacketInfo(
                sourceAddress = sourceAddress,
                sourcePort = sourcePort,
                destAddress = destAddress,
                destPort = destPort,
                sequenceNumber = sequenceNumber,
                ackNumber = ackNumber,
                flags = flags,
                payloadLength = payloadLength,
                payloadStart = payloadStart,
                connectionKey = connectionKey,
                tcpHeaderLength = tcpHeaderLength
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing TCP packet", e)
            null
        }
    }
    
    private suspend fun handleTcpSyn(tcpInfo: TcpPacketInfo) {
        Log.d(TAG, "Processing SYN packet for ${tcpInfo.connectionKey}")
        
        // Check if session already exists
        if (sessionManager.getSession(tcpInfo.connectionKey) != null) {
            Log.w(TAG, "Session already exists for ${tcpInfo.connectionKey}")
            return
        }
        
        // Create new TCP session
        createTcpSession(tcpInfo)
    }
    
    private suspend fun handleTcpAck(tcpInfo: TcpPacketInfo, session: VpnSession?) {
        if (session != null) {
            Log.d(TAG, "TCP ACK received for ${tcpInfo.connectionKey}, session state: ${session.state}")
            
            // Update session sequence numbers
            sessionManager.updateSession(tcpInfo.connectionKey) { currentSession ->
                currentSession.copy(
                    clientSequenceNumber = tcpInfo.sequenceNumber
                )
            }
        } else {
            Log.w(TAG, "Received ACK for unknown connection: ${tcpInfo.connectionKey}")
        }
    }
    
    private suspend fun handleTcpData(tcpInfo: TcpPacketInfo, session: VpnSession?, packet: ByteBuffer) {
        if (session != null && session.isEstablished && tcpInfo.payloadLength > 0) {
            Log.d(TAG, "Handling data for established connection: ${tcpInfo.connectionKey}")
            
            // Extract payload data
            packet.position(tcpInfo.payloadStart)
            val payloadData = ByteArray(tcpInfo.payloadLength)
            packet.get(payloadData)
            
            // Update sequence number
            sessionManager.updateSession(tcpInfo.connectionKey) { currentSession ->
                currentSession.copy(
                    clientSequenceNumber = tcpInfo.sequenceNumber + tcpInfo.payloadLength
                )
            }
            
            // Send data through socket
            handlerScope.launch {
                try {
                    session.socket?.getOutputStream()?.write(payloadData)
                    session.socket?.getOutputStream()?.flush()
                    Log.v(TAG, "Sent ${tcpInfo.payloadLength} bytes to socket for ${tcpInfo.connectionKey}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send data through socket for ${tcpInfo.connectionKey}", e)
                    sessionManager.removeSession(tcpInfo.connectionKey)
                }
            }
        } else {
            Log.w(TAG, "Received data for non-established connection: ${tcpInfo.connectionKey} " +
                  "(session exists: ${session != null}, established: ${session?.isEstablished})")
        }
    }
    
    private suspend fun handleTcpFin(tcpInfo: TcpPacketInfo, session: VpnSession?) {
        Log.d(TAG, "TCP FIN received for ${tcpInfo.connectionKey}")
        sessionManager.removeSession(tcpInfo.connectionKey)
    }
    
    private suspend fun handleTcpReset(tcpInfo: TcpPacketInfo, session: VpnSession?) {
        Log.d(TAG, "TCP RST received for ${tcpInfo.connectionKey}")
        sessionManager.removeSession(tcpInfo.connectionKey)
    }
    
    private suspend fun createTcpSession(tcpInfo: TcpPacketInfo) {
        val sessionId = sessionIdCounter.incrementAndGet()
        val serverInitialSeq = System.currentTimeMillis() and 0xFFFFFFFFL
        
        // Create session and send SYN-ACK immediately
        val session = VpnSession(
            sessionId = sessionId,
            connectionKey = tcpInfo.connectionKey,
            sourceAddress = tcpInfo.sourceAddress,
            sourcePort = tcpInfo.sourcePort,
            destAddress = tcpInfo.destAddress,
            destPort = tcpInfo.destPort,
            protocol = 6,
            localSequenceNumber = serverInitialSeq,
            remoteSequenceNumber = serverInitialSeq,
            clientSequenceNumber = tcpInfo.sequenceNumber,
            lastAckNumber = tcpInfo.sequenceNumber + 1,
            state = TCPState.SYN_SENT,
            isEstablished = false
        )
        
        sessionManager.createSession(session)
        
        // Send SYN-ACK immediately
        val synAckPacket = createSynAckPacket(session)
        if (synAckPacket != null) {
            packetProcessor.sendPacket(synAckPacket)
            Log.d(TAG, "SYN-ACK sent for ${tcpInfo.connectionKey}")
            
            // Update sequence number
            sessionManager.updateSession(tcpInfo.connectionKey) { currentSession ->
                currentSession.copy(
                    localSequenceNumber = currentSession.localSequenceNumber + 1
                )
            }
        }
        
        // Start SOCKS connection in background
        handlerScope.launch {
            establishSocksConnection(tcpInfo, sessionId)
        }
    }
    
    private suspend fun establishSocksConnection(tcpInfo: TcpPacketInfo, sessionId: Int) {
        try {
            Log.d(TAG, "Starting SOCKS connection for ${tcpInfo.connectionKey}")
            
            val targetHost = tcpInfo.destAddress.hostAddress ?: "unknown"
            val targetPort = tcpInfo.destPort
            
            // Check routing decision
            if (!connectionRouter.shouldProxy(targetHost, targetPort)) {
                Log.d(TAG, "Direct connection for ${tcpInfo.connectionKey}")
                // TODO: Implement direct connection
                return
            }
            
            // Create SOCKS connection
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", 1080), 10000)
            
            // Perform SOCKS5 handshake
            if (!performSocks5Handshake(socket, targetHost, targetPort)) {
                socket.close()
                Log.e(TAG, "SOCKS5 handshake failed for ${tcpInfo.connectionKey}")
                sendTcpReset(tcpInfo)
                sessionManager.removeSession(tcpInfo.connectionKey)
                return
            }
            
            // Update session with established connection
            val dataRelayJob = startDataRelay(tcpInfo.connectionKey, socket)
            
            sessionManager.updateSession(tcpInfo.connectionKey) { currentSession ->
                currentSession.copy(
                    socket = socket,
                    relayJob = dataRelayJob,
                    state = TCPState.ESTABLISHED,
                    isEstablished = true
                )
            }
            
            Log.d(TAG, "SOCKS connection established for ${tcpInfo.connectionKey}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish SOCKS connection for ${tcpInfo.connectionKey}", e)
            sendTcpReset(tcpInfo)
            sessionManager.removeSession(tcpInfo.connectionKey)
        }
    }
    
    private suspend fun performSocks5Handshake(socket: Socket, targetHost: String, targetPort: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                
                socket.soTimeout = 10000
                
                // Send greeting
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                
                // Read greeting response
                val greetingResponse = ByteArray(2)
                val greetingBytesRead = input.read(greetingResponse)
                
                if (greetingBytesRead != 2 || greetingResponse[0] != 0x05.toByte() || greetingResponse[1] != 0x00.toByte()) {
                    Log.e(TAG, "SOCKS5 greeting failed")
                    return@withContext false
                }
                
                // Send connect request
                val connectRequest = createSocks5ConnectRequest(targetHost, targetPort)
                output.write(connectRequest)
                output.flush()
                
                // Read connect response
                val responseHeader = ByteArray(4)
                val headerBytesRead = input.read(responseHeader)
                
                if (headerBytesRead != 4 || responseHeader[0] != 0x05.toByte() || responseHeader[1] != 0x00.toByte()) {
                    Log.e(TAG, "SOCKS5 connect failed")
                    return@withContext false
                }
                
                // Read bound address and port (ignore for now)
                val addressType = responseHeader[3]
                val addressLength = when (addressType) {
                    0x01.toByte() -> 4  // IPv4
                    0x03.toByte() -> {
                        val lengthByte = input.read()
                        if (lengthByte == -1) return@withContext false
                        lengthByte
                    }
                    0x04.toByte() -> 16 // IPv6
                    else -> return@withContext false
                }
                
                val addressBytes = ByteArray(addressLength)
                input.read(addressBytes)
                val portBytes = ByteArray(2)
                input.read(portBytes)
                
                socket.soTimeout = 0
                Log.d(TAG, "SOCKS5 handshake completed for $targetHost:$targetPort")
                return@withContext true
                
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 handshake error", e)
                return@withContext false
            }
        }
    }
    
    private fun createSocks5ConnectRequest(targetHost: String, targetPort: Int): ByteArray {
        return try {
            val ipAddress = InetAddress.getByName(targetHost)
            if (ipAddress.address.size == 4) {
                // IPv4 address
                val request = ByteArray(10)
                request[0] = 0x05 // Version
                request[1] = 0x01 // Connect command
                request[2] = 0x00 // Reserved
                request[3] = 0x01 // IPv4 address type
                System.arraycopy(ipAddress.address, 0, request, 4, 4)
                request[8] = (targetPort shr 8).toByte()
                request[9] = (targetPort and 0xFF).toByte()
                request
            } else {
                // Use domain name format
                createDomainConnectRequest(targetHost, targetPort)
            }
        } catch (e: Exception) {
            // Use domain name format
            createDomainConnectRequest(targetHost, targetPort)
        }
    }
    
    private fun createDomainConnectRequest(targetHost: String, targetPort: Int): ByteArray {
        val hostBytes = targetHost.toByteArray()
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 0x05 // Version
        request[1] = 0x01 // Connect command
        request[2] = 0x00 // Reserved
        request[3] = 0x03 // Domain name type
        request[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
        request[5 + hostBytes.size] = (targetPort shr 8).toByte()
        request[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        return request
    }
    
    private fun startDataRelay(connectionKey: String, socket: Socket): Job {
        return handlerScope.launch {
            try {
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(8192)
                
                while (isActive && !socket.isClosed) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    
                    if (bytesRead > 0) {
                        val session = sessionManager.getSession(connectionKey)
                        if (session != null) {
                            val responsePacket = createTcpResponsePacket(session, buffer.copyOf(bytesRead))
                            if (responsePacket != null) {
                                packetProcessor.sendPacket(responsePacket)
                                Log.v(TAG, "Relayed $bytesRead bytes back to TUN for $connectionKey")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error in data relay for $connectionKey", e)
                }
            } finally {
                sessionManager.removeSession(connectionKey)
            }
        }
    }
    
    private fun createSynAckPacket(session: VpnSession): ByteBuffer? {
        return createTcpPacket(
            sourceAddress = session.destAddress,
            sourcePort = session.destPort,
            destAddress = session.sourceAddress,
            destPort = session.sourcePort,
            sequenceNumber = session.localSequenceNumber,
            ackNumber = session.lastAckNumber,
            flags = 0x12, // SYN + ACK
            data = ByteArray(0)
        )
    }
    
    private fun createTcpResponsePacket(session: VpnSession, data: ByteArray): ByteBuffer? {
        val packet = createTcpPacket(
            sourceAddress = session.destAddress,
            sourcePort = session.destPort,
            destAddress = session.sourceAddress,
            destPort = session.sourcePort,
            sequenceNumber = session.remoteSequenceNumber,
            ackNumber = session.localSequenceNumber + 1,
            flags = 0x18, // PSH + ACK
            data = data
        )
        
        // Update sequence number
        sessionManager.updateSession(session.connectionKey) { currentSession ->
            currentSession.copy(
                remoteSequenceNumber = currentSession.remoteSequenceNumber + data.size
            )
        }
        
        return packet
    }
    
    private fun sendTcpReset(tcpInfo: TcpPacketInfo) {
        val resetPacket = createTcpPacket(
            sourceAddress = tcpInfo.destAddress,
            sourcePort = tcpInfo.destPort,
            destAddress = tcpInfo.sourceAddress,
            destPort = tcpInfo.sourcePort,
            sequenceNumber = 0,
            ackNumber = tcpInfo.sequenceNumber + 1,
            flags = 0x14, // RST + ACK
            data = ByteArray(0)
        )
        
        if (resetPacket != null) {
            handlerScope.launch {
                packetProcessor.sendPacket(resetPacket)
                Log.d(TAG, "TCP RST sent to ${tcpInfo.sourceAddress.hostAddress}:${tcpInfo.sourcePort}")
            }
        }
    }
    
    private fun createTcpPacket(
        sourceAddress: InetAddress,
        sourcePort: Int,
        destAddress: InetAddress,
        destPort: Int,
        sequenceNumber: Long,
        ackNumber: Long,
        flags: Int,
        data: ByteArray
    ): ByteBuffer? {
        return try {
            val totalLength = 20 + 20 + data.size // IP header + TCP header + data
            val packet = ByteBuffer.allocate(totalLength)
            
            // Create IP header
            packet.put((0x45).toByte()) // Version 4, Header length 5*4=20
            packet.put(0) // Type of service
            packet.putShort(totalLength.toShort()) // Total length
            packet.putShort(0) // Identification
            packet.putShort(0) // Flags and fragment offset
            packet.put(64) // TTL
            packet.put(6) // Protocol (TCP)
            packet.putShort(0) // Header checksum
            packet.put(sourceAddress.address) // Source IP
            packet.put(destAddress.address) // Destination IP
            
            // Create TCP header
            packet.putShort(sourcePort.toShort()) // Source port
            packet.putShort(destPort.toShort()) // Destination port
            packet.putInt(sequenceNumber.toInt()) // Sequence number
            packet.putInt(ackNumber.toInt()) // Ack number
            packet.put((0x50).toByte()) // Header length (5*4=20 bytes)
            packet.put(flags.toByte()) // Flags
            packet.putShort(8192) // Window size
            packet.putShort(0) // Checksum
            packet.putShort(0) // Urgent pointer
            
            // Add data
            packet.put(data)
            
            // Calculate IP checksum
            packet.putShort(10, 0) // Zero out checksum field
            val ipChecksum = calculateChecksum(packet, 0, 20)
            packet.putShort(10, ipChecksum)

            // Calculate TCP checksum
            // Create pseudo-header
            val pseudoHeader = ByteBuffer.allocate(12 + 20 + data.size) // Pseudo-header + TCP header + data
            pseudoHeader.put(sourceAddress.address)
            pseudoHeader.put(destAddress.address)
            pseudoHeader.put(0.toByte()) // Reserved
            pseudoHeader.put(6.toByte()) // Protocol (TCP)
            pseudoHeader.putShort((20 + data.size).toShort()) // TCP length (header + data)

            // Copy TCP header and data to pseudo-header buffer
            // The TCP header starts at offset 20 in the original packet.
            // The TCP data follows immediately after the TCP header.
            // The length of (TCP header + data) is (20 + data.size).
            pseudoHeader.put(packet.array(), packet.arrayOffset() + 20, 20 + data.size)

            // Reset TCP checksum field in pseudo-header buffer for calculation
            // The TCP checksum is at offset 16 within the TCP header part of the pseudoHeader.
            // The TCP header part starts at offset 12 in pseudoHeader (after pseudo-header fields).
            pseudoHeader.putShort(12 + 16, 0)

            pseudoHeader.flip() // Prepare for reading by calculateChecksum

            val tcpChecksum = calculateChecksum(pseudoHeader, 0, pseudoHeader.limit())
            // Restore packet's original position before modifying it, as calculateChecksum for IP might have changed it.
            // However, IP checksum is calculated first, and packet position is restored there.
            // Here, we are modifying the main 'packet' at TCP checksum offset.
            packet.putShort(20 + 16, tcpChecksum) // Set TCP checksum in original packet (offset 20 for IP, 16 for TCP checksum)

            packet.rewind() // Rewind to the beginning before returning
            packet
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating TCP packet", e)
            null
        }
    }
    
    private fun calculateChecksum(buffer: ByteBuffer, offset: Int, length: Int): Short {
        var sum = 0
        val originalPosition = buffer.position()
        buffer.position(offset)

        for (i in 0 until length / 2) {
            sum += buffer.short.toInt() and 0xFFFF
        }

        // If length is odd, add the last byte (padded with 0)
        if (length % 2 != 0) {
            sum += (buffer.get().toInt() and 0xFF) shl 8
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        buffer.position(originalPosition) // Restore buffer position
        return sum.toShort().inv()
    }

    private fun getTCPFlagsString(flags: Int): String {
        val flagList = mutableListOf<String>()
        if ((flags and 0x01) != 0) flagList.add("FIN")
        if ((flags and 0x02) != 0) flagList.add("SYN")
        if ((flags and 0x04) != 0) flagList.add("RST")
        if ((flags and 0x08) != 0) flagList.add("PSH")
        if ((flags and 0x10) != 0) flagList.add("ACK")
        if ((flags and 0x20) != 0) flagList.add("URG")
        return if (flagList.isEmpty()) "NONE" else flagList.joinToString("+")
    }
    
    /**
     * Stop the TCP handler
     */
    fun stop() {
        handlerScope.cancel()
        Log.i(TAG, "TCP handler stopped")
    }
    
    data class TcpPacketInfo(
        val sourceAddress: InetAddress,
        val sourcePort: Int,
        val destAddress: InetAddress,
        val destPort: Int,
        val sequenceNumber: Long,
        val ackNumber: Long,
        val flags: Int,
        val payloadLength: Int,
        val payloadStart: Int,
        val connectionKey: String,
        val tcpHeaderLength: Int
    )
}
