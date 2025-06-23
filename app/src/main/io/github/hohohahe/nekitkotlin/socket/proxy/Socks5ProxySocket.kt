package io.github.hohohahe.nekitkotlin.socket.proxy

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

// SOCKS5 constants
private const val SOCKS_VERSION_5: Byte = 0x05
private const val METHOD_NO_AUTHENTICATION_REQUIRED: Byte = 0x00
// private const val METHOD_GSSAPI: Byte = 0x01
// private const val METHOD_USERNAME_PASSWORD: Byte = 0x02
private const val METHOD_NO_ACCEPTABLE_METHODS: Byte = 0xFF.toByte()

private const val CMD_CONNECT: Byte = 0x01
// private const val CMD_BIND: Byte = 0x02
// private const val CMD_UDP_ASSOCIATE: Byte = 0x03

private const val ATYP_IPV4: Byte = 0x01
private const val ATYP_DOMAINNAME: Byte = 0x03
private const val ATYP_IPV6: Byte = 0x04

// Reply codes
private const val REP_SUCCEEDED: Byte = 0x00
private const val REP_GENERAL_SOCKS_SERVER_FAILURE: Byte = 0x01
// ... other reply codes

class Socks5ProxySocket(private val clientSocket: RawTcpSocket) : ProxySocket {

    override val isOpen: Boolean get() = clientSocket.isOpen
    override val localAddress: IpAddress? get() = clientSocket.localAddress
    override val remoteAddress: IpAddress? get() = clientSocket.remoteAddress

    private val connectSessionChannel = Channel<ConnectSession>(Channel.CONFLATED)

    override fun getConnectSession(): Flow<ConnectSession> {
        // Similar to HttpProxySocket, parsing should be triggered externally or on first call.
        // For now, assume an explicit `handleIncomingConnection` will be called.
        return connectSessionChannel.receiveAsFlow()
    }

    suspend fun handleIncomingConnection() {
        try {
            performHandshake()
            val session = readRequestAndParseSession()
            connectSessionChannel.send(session)
            connectSessionChannel.close() // One session per socket
        } catch (e: Exception) {
            logger.error(e) { "SOCKS5 handshake/request failed for ${remoteAddress}: ${e.message}" }
            // Try to send a failure reply if possible, depending on where the error occurred.
            // If handshake failed early, client might not expect a SOCKS5 reply.
            if (e is Socks5ErrorReplyException) {
                 try {
                    sendReply(e.replyCode, null, null) // Use the specific reply code from the exception
                } catch (replyEx: Exception) {
                    logger.error(replyEx) {"Failed to send SOCKS5 error reply"}
                }
            } else if (clientSocket.isOpen) {
                // Generic failure if not a specific SOCKS error
                try {
                    sendReply(REP_GENERAL_SOCKS_SERVER_FAILURE, null, null)
                } catch (replyEx: Exception) {
                     logger.error(replyEx) {"Failed to send generic SOCKS5 error reply"}
                }
            }
            connectSessionChannel.close(e)
            close()
        }
    }

    private suspend fun performHandshake() {
        // 1. Read client's method selection message
        // +----+----------+----------+
        // |VER | NMETHODS | METHODS  |
        // +----+----------+----------+
        // | 1  |    1     | 1 to 255 |
        // +----+----------+----------+
        logger.debug { "SOCKS5: Reading handshake from ${remoteAddress}" }
        val verNmethods = readBytes(2)
        if (verNmethods[0] != SOCKS_VERSION_5) {
            throw Socks5ErrorReplyException("Unsupported SOCKS version: ${verNmethods[0]}", REP_GENERAL_SOCKS_SERVER_FAILURE)
        }
        val nMethods = verNmethods[1].toInt() and 0xFF
        if (nMethods == 0) {
             throw Socks5ErrorReplyException("No authentication methods provided by client", METHOD_NO_ACCEPTABLE_METHODS)
        }
        val methods = readBytes(nMethods)

        // 2. Select a method (only NO_AUTHENTICATION_REQUIRED is supported for now)
        var selectedMethod = METHOD_NO_ACCEPTABLE_METHODS
        for (method in methods) {
            if (method == METHOD_NO_AUTHENTICATION_REQUIRED) {
                selectedMethod = METHOD_NO_AUTHENTICATION_REQUIRED
                break
            }
        }
        logger.debug { "SOCKS5: Client ${remoteAddress} offered methods: ${methods.joinToString()}, selected: $selectedMethod" }


        // 3. Send server's method selection
        // +----+--------+
        // |VER | METHOD |
        // +----+--------+
        // | 1  |   1    |
        // +----+--------+
        val serverSelection = ByteBuffer.allocate(2)
        serverSelection.put(SOCKS_VERSION_5)
        serverSelection.put(selectedMethod)
        serverSelection.flip()
        clientSocket.write(serverSelection)

        if (selectedMethod == METHOD_NO_ACCEPTABLE_METHODS) {
            throw Socks5ErrorReplyException("No acceptable authentication methods for client ${remoteAddress}", METHOD_NO_ACCEPTABLE_METHODS)
        }
        // If other auth methods were supported, they would be handled here.
    }

    private suspend fun readRequestAndParseSession(): ConnectSession {
        // Read SOCKS5 request
        // +----+-----+-------+------+----------+----------+
        // |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
        // +----+-----+-------+------+----------+----------+
        // | 1  |  1  | X'00' |  1   | Variable |    2     |
        // +----+-----+-------+------+----------+----------+
        logger.debug { "SOCKS5: Reading request from ${remoteAddress}" }
        val verCmdRsvAtyp = readBytes(4)
        if (verCmdRsvAtyp[0] != SOCKS_VERSION_5) {
            throw Socks5ErrorReplyException("Invalid SOCKS version in request: ${verCmdRsvAtyp[0]}", REP_GENERAL_SOCKS_SERVER_FAILURE)
        }
        if (verCmdRsvAtyp[1] != CMD_CONNECT) {
            // Only CMD_CONNECT is supported for now
            throw Socks5ErrorReplyException("Unsupported SOCKS command: ${verCmdRsvAtyp[1]}", REP_GENERAL_SOCKS_SERVER_FAILURE) // Or specific "command not supported"
        }
        // RSV (verCmdRsvAtyp[2]) must be X'00'

        val atyp = verCmdRsvAtyp[3]
        val host: String
        val port: Int

        when (atyp) {
            ATYP_IPV4 -> {
                val addrBytes = readBytes(4)
                host = InetAddress.getByAddress(addrBytes).hostAddress
            }
            ATYP_DOMAINNAME -> {
                val lenByte = readBytes(1)
                val len = lenByte[0].toInt() and 0xFF
                val domainBytes = readBytes(len)
                host = String(domainBytes, StandardCharsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val addrBytes = readBytes(16)
                host = InetAddress.getByAddress(addrBytes).hostAddress
            }
            else -> {
                throw Socks5ErrorReplyException("Unsupported address type: $atyp", REP_GENERAL_SOCKS_SERVER_FAILURE) // Or specific "address type not supported"
            }
        }

        val portBytes = readBytes(2)
        port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        if (port <= 0 || port > 65535) {
            throw Socks5ErrorReplyException("Invalid port number: $port", REP_GENERAL_SOCKS_SERVER_FAILURE)
        }

        val session = ConnectSession(host, Port(port))
        logger.info { "SOCKS5 CONNECT request from ${remoteAddress} for ${session.host}:${session.port.value} (ATYP: $atyp)" }
        return session
    }

    private suspend fun sendReply(rep: Byte, boundAddr: IpAddress?, boundPort: Port?) {
        // +----+-----+-------+------+----------+----------+
        // |VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
        // +----+-----+-------+------+----------+----------+
        // | 1  |  1  | X'00' |  1   | Variable |    2     |
        // +----+-----+-------+------+----------+----------+
        // For CONNECT, BND.ADDR and BND.PORT are typically the server's address/port
        // on the connection to the target, or can be zeroed if not relevant.
        // For this proxy, let's use the local address of the clientSocket if available, else 0.0.0.0.

        val replyBuffer = ByteBuffer.allocate(32) // Max size for domain name could be larger, but typical replies are small
        replyBuffer.put(SOCKS_VERSION_5)
        replyBuffer.put(rep)
        replyBuffer.put(0x00) // RSV

        // Simplified BND.ADDR/PORT for now, using 0.0.0.0 and port 0
        // A more complete implementation would reflect the actual bound address for the outgoing connection.
        var bndAddrBytes: ByteArray
        var atyp: Byte

        // If we have a specific bound address from the adapter socket, use it.
        // Otherwise, default to IPv4 0.0.0.0
        // This information might not be available at all stages, especially for error replies before adapter connection.
        val effectiveBoundAddr = boundAddr?.value ?: "0.0.0.0"
        val effectiveBoundPortVal = boundPort?.value ?: 0

        try {
            val inetBndAddr = InetAddress.getByName(effectiveBoundAddr)
            if (inetBndAddr.address.size == 4) {
                atyp = ATYP_IPV4
                bndAddrBytes = inetBndAddr.address
            } else if (inetBndAddr.address.size == 16) {
                atyp = ATYP_IPV6
                bndAddrBytes = inetBndAddr.address
            } else {
                // Fallback or error if address type is unknown (should not happen with getByName)
                logger.warn { "SOCKS5: Could not determine BND.ADDR type for $effectiveBoundAddr, defaulting to 0.0.0.0" }
                atyp = ATYP_IPV4
                bndAddrBytes = InetAddress.getByName("0.0.0.0").address
            }
        } catch (e: Exception) {
             logger.warn(e) { "SOCKS5: Error resolving BND.ADDR $effectiveBoundAddr, defaulting to 0.0.0.0" }
             atyp = ATYP_IPV4
             bndAddrBytes = InetAddress.getByName("0.0.0.0").address
        }


        replyBuffer.put(atyp)
        replyBuffer.put(bndAddrBytes)
        replyBuffer.putShort(effectiveBoundPortVal.toShort())

        replyBuffer.flip()
        logger.debug { "SOCKS5: Sending reply to ${remoteAddress}: REP=$rep, BND.ADDR=${IpAddress(effectiveBoundAddr)}, BND.PORT=$effectiveBoundPortVal" }
        clientSocket.write(replyBuffer)
    }


    override suspend fun respondToSuccess() {
        // The adapter socket's local address/port could be used as BND.ADDR/BND.PORT
        // This requires Tunnel to pass this info back to ProxySocket.
        // For now, send a generic success with 0.0.0.0:0 as bound address.
        // This information is available on the ConnectSession after adapter connection.
        // We need to retrieve it from the ConnectSession that was processed by the adapter.
        // Let's assume for now that the Tunnel doesn't pass this back, and we use defaults.
        // A better design would be respondToSuccess(finalConnectSession: ConnectSession)
        logger.debug { "SOCKS5: Responding SUCCEEDED to ${remoteAddress}" }
        sendReply(REP_SUCCEEDED, localAddress, Port( (clientSocket.localAddress as? InetSocketAddress)?.port ?: 0))
    }

    override suspend fun respondToFailure(reason: String) {
        // This is a generic failure after request parsing.
        // Specific failures during handshake/request are handled by throwing Socks5ErrorReplyException.
        logger.warn { "SOCKS5: Responding GENERAL_FAILURE to ${remoteAddress} due to: $reason" }
        sendReply(REP_GENERAL_SOCKS_SERVER_FAILURE, null, null)
    }

    private suspend fun readBytes(count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        var totalBytesRead = 0
        while (totalBytesRead < count) {
            if (!clientSocket.isOpen) throw IOException("SOCKS5: Connection closed by ${remoteAddress} while reading.")
            val bytesRead = clientSocket.read(buffer)
            if (bytesRead == -1) {
                throw IOException("SOCKS5: Connection closed by ${remoteAddress} (EOF) while expecting $count bytes.")
            }
            if (bytesRead == 0) {
                // Avoid busy loop if read returns 0 immediately (e.g. non-blocking socket with no data)
                kotlinx.coroutines.delay(10)
                continue
            }
            totalBytesRead += bytesRead
        }
        buffer.flip()
        return buffer.array().copyOf(buffer.limit()) // Ensure only read bytes are returned
    }


    override suspend fun read(buffer: ByteBuffer): Int {
        return clientSocket.read(buffer)
    }

    override suspend fun write(buffer: ByteBuffer): Int {
        return clientSocket.write(buffer)
    }

    override fun close() {
        logger.debug { "Closing Socks5ProxySocket for ${remoteAddress}." }
         if (!connectSessionChannel.isClosedForSend) {
            connectSessionChannel.close(IOException("Socks5ProxySocket closed before session could be established."))
        }
        clientSocket.close()
    }
}

// Custom exception to carry SOCKS5 reply code
class Socks5ErrorReplyException(message: String, val replyCode: Byte, cause: Throwable? = null) : IOException(message, cause)
