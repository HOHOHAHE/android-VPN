package io.github.hohohahe.nekitkotlin.socket.adapter

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket // Default implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

class HttpAdapterSocket(
    private val proxyHost: String,
    private val proxyPort: Port,
    // Potentially add username/password for proxy authentication later
    private val rawTcpSocket: RawTcpSocket = NettyRawTcpSocket()
) : AdapterSocket {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    override val isOpen: Boolean get() = rawTcpSocket.isOpen && _isReady.value // Considered open only when handshake is done
    override val localAddress: IpAddress? get() = rawTcpSocket.localAddress
    override val remoteAddress: IpAddress? get() = rawTcpSocket.remoteAddress // This will be the upstream proxy's address

    private var targetSession: ConnectSession? = null

    override suspend fun openSocket(session: ConnectSession): RawTcpSocket {
        if (rawTcpSocket.isOpen) { // Check underlying socket state
            throw IllegalStateException("Adapter socket is already open or connection attempt in progress.")
        }
        this.targetSession = session
        logger.debug { "HttpAdapter connecting to upstream proxy $proxyHost:${proxyPort.value} for target ${session.host}:${session.port.value}" }

        try {
            // 1. Connect to the upstream HTTP proxy server
            rawTcpSocket.connect(proxyHost, proxyPort)
            logger.info { "HttpAdapter connected to upstream proxy $proxyHost:${proxyPort.value}" }

            // 2. Send HTTP CONNECT request
            val connectRequest = buildConnectRequest(session)
            val requestBuffer = ByteBuffer.wrap(connectRequest.toByteArray(StandardCharsets.US_ASCII))
            rawTcpSocket.write(requestBuffer)
            logger.debug { "HttpAdapter sent CONNECT request for ${session.host}:${session.port.value}" }

            // 3. Read HTTP response
            val response = readProxyResponse()
            logger.trace { "HttpAdapter received response from proxy: $response" }
            parseProxyResponse(response, session)

            _isReady.value = true
            logger.info { "HttpAdapter successfully established tunnel via proxy $proxyHost:${proxyPort.value} to ${session.host}:${session.port.value}" }

        } catch (e: Exception) {
            _isReady.value = false
            logger.error(e) { "HttpAdapter failed for target ${session.host}:${session.port.value} via proxy $proxyHost:${proxyPort.value}: ${e.message}" }
            close()
            throw e
        }
        return rawTcpSocket
    }

    private fun buildConnectRequest(session: ConnectSession): String {
        // Basic CONNECT request. Proxy authentication (Proxy-Authorization header) can be added here if needed.
        return "CONNECT ${session.host}:${session.port.value} HTTP/1.1\r\n" +
               "Host: ${session.host}:${session.port.value}\r\n" + // Required by some proxies
               "Proxy-Connection: Keep-Alive\r\n" + // Optional, some proxies might use it
               "User-Agent: nekit-kotlin/0.1\r\n" + // Optional
               "\r\n"
    }

    private suspend fun readProxyResponse(): String {
        val responseBuffer = ByteArrayOutputStream()
        val tempBuffer = ByteBuffer.allocate(1024)
        var headersEnded = false

        // Read until


        // Add a timeout or max read limit to prevent infinite loops on bad proxy responses
        withContext(Dispatchers.IO) { // Ensure non-blocking read
            val startTime = System.currentTimeMillis()
            val timeoutMs = 10000 // 10 seconds timeout for proxy response

            while (rawTcpSocket.isOpen && !headersEnded) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    throw IOException("Timeout waiting for HTTP proxy response headers from $proxyHost:${proxyPort.value}")
                }
                tempBuffer.clear()
                val bytesRead = rawTcpSocket.read(tempBuffer)
                if (bytesRead == -1) {
                    throw IOException("Connection closed by proxy $proxyHost:${proxyPort.value} while reading response.")
                }
                if (bytesRead > 0) {
                    tempBuffer.flip()
                    val bytes = ByteArray(tempBuffer.remaining())
                    tempBuffer.get(bytes)
                    responseBuffer.write(bytes)

                    val currentResponse = responseBuffer.toByteArray()
                    if (currentResponse.size >= 4) {
                        for (i in 0..currentResponse.size - 4) {
                            if (currentResponse[i] == '\r'.code.toByte() &&
                                currentResponse[i+1] == '\n'.code.toByte() &&
                                currentResponse[i+2] == '\r'.code.toByte() &&
                                currentResponse[i+3] == '\n'.code.toByte()) {
                                headersEnded = true
                                // Any data read past the double CRLF is part of the tunnelled stream.
                                // For CONNECT, there shouldn't be a body with the 200 OK response.
                                // If data was read past headers, it would need careful handling.
                                // We assume the proxy sends 200 OK then the raw stream.
                                break
                            }
                        }
                    }
                } else {
                     kotlinx.coroutines.delay(10) // Avoid busy loop if read returns 0
                }
            }
        }
        if (!headersEnded) {
            throw IOException("Malformed HTTP proxy response: Headers did not end with \r\n\r\n from $proxyHost:${proxyPort.value}")
        }
        return responseBuffer.toString(StandardCharsets.US_ASCII.name())
    }

    private fun parseProxyResponse(response: String, session: ConnectSession) {
        val lines = response.lines()
        if (lines.isEmpty()) {
            throw IOException("Empty response from proxy $proxyHost:${proxyPort.value} for target ${session.host}:${session.port.value}")
        }
        val statusLine = lines[0]
        val parts = statusLine.split(" ", limit = 3)
        if (parts.size < 2) {
            throw IOException("Malformed status line from proxy: '$statusLine'")
        }
        // parts[0] is HTTP version, e.g., "HTTP/1.1"
        val statusCode = parts[1].toIntOrNull()
        if (statusCode == null || statusCode != 200) {
            val reason = if (parts.size > 2) parts[2] else "Unknown Error"
            throw IOException("Proxy $proxyHost:${proxyPort.value} denied CONNECT request for ${session.host}:${session.port.value}. Status: $statusCode $reason. Full response: $response")
        }
        // If we need to parse other headers from proxy (e.g., for connection persistence), do it here.
    }


    override suspend fun read(buffer: ByteBuffer): Int {
        if (!_isReady.value || !rawTcpSocket.isOpen) {
            logger.warn { "Attempting to read from a non-ready or closed HttpAdapterSocket." }
            return -1
        }
        return rawTcpSocket.read(buffer)
    }

    override suspend fun write(buffer: ByteBuffer): Int {
        if (!_isReady.value || !rawTcpSocket.isOpen) {
            logger.warn { "Attempting to write to a non-ready or closed HttpAdapterSocket." }
            throw IOException("HttpAdapterSocket is not ready or closed for writing.")
        }
        return rawTcpSocket.write(buffer)
    }

    override fun close() {
        logger.debug { "Closing HttpAdapterSocket for proxy $proxyHost:${proxyPort.value}, target ${targetSession?.host}" }
        _isReady.value = false
        if (rawTcpSocket.isOpen) {
            rawTcpSocket.close()
        }
    }
}
