package io.github.hohohahe.nekitkotlin.socket.adapter

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket // Default implementation
import kotlinx.coroutines.Dispatchers // Added for withContext if needed for DNS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext // Added for DNS resolution
import mu.KotlinLogging
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException // Specific exception for DNS
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

// SOCKS5 constants (can be shared if moved to a common place)
private const val SOCKS_VERSION_5: Byte = 0x05
private const val METHOD_NO_AUTHENTICATION_REQUIRED: Byte = 0x00
private const val METHOD_NO_ACCEPTABLE_METHODS: Byte = 0xFF.toByte()
private const val CMD_CONNECT: Byte = 0x01
private const val ATYP_IPV4: Byte = 0x01
private const val ATYP_DOMAINNAME: Byte = 0x03
private const val ATYP_IPV6: Byte = 0x04
private const val REP_SUCCEEDED: Byte = 0x00


class Socks5AdapterSocket(
    private val proxyHost: String,
    private val proxyPort: Port,
    // Potentially add username/password for SOCKS5 auth later
    private val rawTcpSocket: RawTcpSocket = NettyRawTcpSocket()
) : AdapterSocket {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    override val isOpen: Boolean get() = rawTcpSocket.isOpen && _isReady.value
    override val localAddress: IpAddress? get() = rawTcpSocket.localAddress
    override val remoteAddress: IpAddress? get() = rawTcpSocket.remoteAddress // Upstream proxy's address

    private var targetSession: ConnectSession? = null


    override suspend fun openSocket(session: ConnectSession): RawTcpSocket {
         if (rawTcpSocket.isOpen) {
            throw IllegalStateException("Adapter socket is already open or connection attempt in progress.")
        }
        this.targetSession = session
        logger.debug { "Socks5Adapter connecting to upstream SOCKS5 proxy $proxyHost:${proxyPort.value} for target ${session.host}:${session.port.value}" }

        try {
            // 1. Connect to SOCKS5 proxy server
            rawTcpSocket.connect(proxyHost, proxyPort)
            logger.info { "Socks5Adapter connected to upstream proxy $proxyHost:${proxyPort.value}" }

            // 2. Perform SOCKS5 handshake
            performHandshake()

            // 3. Send SOCKS5 connect request
            sendConnectRequest(session)

            // 4. Read SOCKS5 reply
            readAndProcessReply(session)

            _isReady.value = true
            logger.info { "Socks5Adapter successfully established tunnel via proxy $proxyHost:${proxyPort.value} to ${session.host}:${session.port.value}" }

        } catch (e: Exception) {
            _isReady.value = false
            logger.error(e) { "Socks5Adapter failed for target ${session.host}:${session.port.value} via proxy $proxyHost:${proxyPort.value}: ${e.message}" }
            close()
            throw e
        }
        return rawTcpSocket
    }

    private suspend fun performHandshake() {
        val handshakeRequest = ByteBuffer.allocate(3)
        handshakeRequest.put(SOCKS_VERSION_5)
        handshakeRequest.put(0x01) // NMETHODS = 1
        handshakeRequest.put(METHOD_NO_AUTHENTICATION_REQUIRED)
        handshakeRequest.flip()
        rawTcpSocket.write(handshakeRequest)
        logger.debug { "Socks5Adapter: Sent handshake to proxy $proxyHost:${proxyPort.value}" }

        val handshakeResponse = readBytes(2, "handshake response")
        if (handshakeResponse[0] != SOCKS_VERSION_5) {
            throw IOException("SOCKS5 proxy $proxyHost:${proxyPort.value} sent invalid version: ${handshakeResponse[0]}")
        }
        if (handshakeResponse[1] == METHOD_NO_ACCEPTABLE_METHODS) {
            throw IOException("SOCKS5 proxy $proxyHost:${proxyPort.value} requires authentication, which is not supported.")
        }
        if (handshakeResponse[1] != METHOD_NO_AUTHENTICATION_REQUIRED) {
            throw IOException("SOCKS5 proxy $proxyHost:${proxyPort.value} selected unsupported auth method: ${handshakeResponse[1]}")
        }
        logger.debug { "Socks5Adapter: Handshake with proxy $proxyHost:${proxyPort.value} successful (NO_AUTH)." }
    }

    private suspend fun sendConnectRequest(session: ConnectSession) {
        val requestBuffer = ByteBuffer.allocate(262)
        requestBuffer.put(SOCKS_VERSION_5)
        requestBuffer.put(CMD_CONNECT)
        requestBuffer.put(0x00) // RSV

        var addressBytes: ByteArray
        var atyp: Byte

        try {
            // Attempt to resolve the hostname to an IP address.
            // This should be done in an IO context if it's a blocking call.
            val inetAddr = withContext(Dispatchers.IO) { // Ensure DNS is non-blocking for coroutine
                InetAddress.getByName(session.host)
            }
            addressBytes = inetAddr.address
            atyp = when (addressBytes.size) {
                4 -> ATYP_IPV4
                16 -> ATYP_IPV6
                else -> {
                    logger.warn("Unexpected address size for ${session.host} (${addressBytes.size} bytes), sending as domain name.")
                    // Re-assign addressBytes to the domain name bytes
                    addressBytes = session.host.toByteArray(StandardCharsets.US_ASCII)
                    if (addressBytes.size > 255) {
                       throw IOException("Domain name ${session.host} too long for SOCKS5 request (max 255 bytes). Length: ${addressBytes.size}")
                    }
                    ATYP_DOMAINNAME // Ensure this branch of 'when' returns ATYP_DOMAINNAME
                }
            }
        } catch (e: UnknownHostException) {
            logger.debug("Could not resolve ${session.host} for SOCKS5 adapter (UnknownHostException), sending as domain name.")
            atyp = ATYP_DOMAINNAME
            addressBytes = session.host.toByteArray(StandardCharsets.US_ASCII)
            if (addressBytes.size > 255) {
                throw IOException("Domain name ${session.host} too long for SOCKS5 request (max 255 bytes). Length: ${addressBytes.size}")
            }
        } catch (e: Exception) { // Catch other potential exceptions from getByName or toByteArray
             logger.warn(e){"Exception during address processing for ${session.host}, sending as domain name."}
             atyp = ATYP_DOMAINNAME
             addressBytes = session.host.toByteArray(StandardCharsets.US_ASCII)
             if (addressBytes.size > 255) {
                throw IOException("Domain name ${session.host} too long for SOCKS5 request (max 255 bytes). Length: ${addressBytes.size}")
            }
        }

        requestBuffer.put(atyp)
        if (atyp == ATYP_DOMAINNAME) {
            // Directly use addressBytes.size.toByte() here
            if (addressBytes.isEmpty()) { // Should not happen if host is not empty
                throw IOException("Cannot send empty domain name in SOCKS5 request.")
            }
            requestBuffer.put(addressBytes.size.toByte())
        }
        requestBuffer.put(addressBytes)
        requestBuffer.putShort(session.port.value.toShort())
        requestBuffer.flip()
        rawTcpSocket.write(requestBuffer)
        logger.debug { "Socks5Adapter: Sent CONNECT command to proxy for ${session.host}:${session.port.value} (ATYP: $atyp, AddrLen: ${addressBytes.size})" }
    }

    private suspend fun readAndProcessReply(session: ConnectSession) {
        val verRepRsvAtyp = readBytes(4, "reply header")
        if (verRepRsvAtyp[0] != SOCKS_VERSION_5) {
            throw IOException("SOCKS5 proxy $proxyHost:${proxyPort.value} sent invalid version in reply: ${verRepRsvAtyp[0]}")
        }
        val replyCode = verRepRsvAtyp[1]
        if (replyCode != REP_SUCCEEDED) {
            throw IOException("SOCKS5 proxy $proxyHost:${proxyPort.value} denied connection for ${session.host}:${session.port.value}. Reply code: $replyCode")
        }

        val atyp = verRepRsvAtyp[3]
        when (atyp) {
            ATYP_IPV4 -> readBytes(4, "IPv4 BND.ADDR")
            ATYP_IPV6 -> readBytes(16, "IPv6 BND.ADDR")
            ATYP_DOMAINNAME -> {
                val lenByte = readBytes(1, "domain length BND.ADDR")
                val len = lenByte[0].toInt() and 0xFF
                if (len > 0) { // Read only if length is positive
                   readBytes(len, "domain BND.ADDR")
                } else if (len < 0) { // Should not happen with toInt() and 0xFF
                    throw IOException("Invalid negative length for domain BND.ADDR: $len")
                }
                // If len is 0, do nothing, it's an empty domain string.
            }
            else -> throw IOException("SOCKS5 proxy $proxyHost:${proxyPort.value} sent unknown address type in reply: $atyp")
        }
        readBytes(2, "BND.PORT")
        logger.debug { "Socks5Adapter: Received SUCCEEDED reply from proxy for ${session.host}:${session.port.value}" }
    }

    private suspend fun readBytes(count: Int, description: String): ByteArray {
        if (count == 0) return byteArrayOf() // Handle request to read 0 bytes
        val buffer = ByteBuffer.allocate(count)
        var totalBytesRead = 0
        val timeoutMs = 10000
        val startTime = System.currentTimeMillis()

        while (totalBytesRead < count) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw IOException("Timeout reading $description from SOCKS5 proxy $proxyHost:${proxyPort.value}")
            }
            if (!rawTcpSocket.isOpen) throw IOException("Connection closed by SOCKS5 proxy $proxyHost:${proxyPort.value} while reading $description.")

            val bytesRead = rawTcpSocket.read(buffer)
            if (bytesRead == -1) {
                throw IOException("EOF from SOCKS5 proxy $proxyHost:${proxyPort.value} while expecting $count bytes for $description.")
            }
            if (bytesRead == 0) {
                kotlinx.coroutines.delay(10)
                continue
            }
            totalBytesRead += bytesRead
        }
        buffer.flip()
        // Ensure only `count` bytes are returned, even if buffer was larger (shouldn't be with allocate(count))
        return buffer.array().copyOfRange(buffer.position(), buffer.limit())
    }


    override suspend fun read(buffer: ByteBuffer): Int {
        if (!_isReady.value || !rawTcpSocket.isOpen) return -1
        return rawTcpSocket.read(buffer)
    }

    override suspend fun write(buffer: ByteBuffer): Int {
        if (!_isReady.value || !rawTcpSocket.isOpen) throw IOException("Socks5AdapterSocket is not ready or closed for writing.")
        return rawTcpSocket.write(buffer)
    }

    override fun close() {
        logger.debug { "Closing Socks5AdapterSocket for proxy $proxyHost:${proxyPort.value}, target ${targetSession?.host}" }
        _isReady.value = false
        if (rawTcpSocket.isOpen) {
            rawTcpSocket.close()
        }
    }
}
