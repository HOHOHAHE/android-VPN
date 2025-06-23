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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

class HttpProxySocket(private val clientSocket: RawTcpSocket) : ProxySocket {

    override val isOpen: Boolean get() = clientSocket.isOpen
    override val localAddress: IpAddress? get() = clientSocket.localAddress
    override val remoteAddress: IpAddress? get() = clientSocket.remoteAddress

    private val connectSessionChannel = Channel<ConnectSession>(Channel.CONFLATED)
    private var httpHeaders = mutableMapOf<String, String>()
    private var requestLine: String = ""


    override fun getConnectSession(): Flow<ConnectSession> {
        // Start parsing logic in a coroutine when this is first called or upon instantiation.
        // For simplicity, let's assume clientSocket is already connected and ready for reading.
        // The actual parsing will be triggered by an explicit call or by Tunnel collecting the flow.
        // This flow will emit one item (ConnectSession or exception).
        // Consider making this an internal function called by open() or similar.
        // For now, let's make it so that collecting this flow triggers the read and parse.
        // This is a simplified model; a real server would have a loop.
        // The current ProxySocket interface implies getConnectSession might be called to initiate.

        // kotlinx.coroutines.GlobalScope.launch { readAndParseHttpRequest() } // Example: launch parsing
        // The above is not ideal. Parsing should be part of the socket's active lifecycle.
        // Let's assume Tunnel will call a method that internally calls readAndParse.
        // Or, getConnectSession itself can be suspending and do the work.
        // The current interface is Flow<ConnectSession>, so it should be non-suspending.
        // The parsing work needs to happen before this flow can emit.
        // This suggests getConnectSession() should be a suspend fun or parsing happens in constructor/init.
        // Let's assume for now an explicit `parse` method will be called by the server/tunnel orchestrator
        // which then populates the channel.
        // For this subtask, we'll provide a separate suspend fun to do the parsing.

        return connectSessionChannel.receiveAsFlow()
    }

    // This method would be called by the entity managing this ProxySocket (e.g., a ProxyServer implementation)
    // after accepting a new client connection.
    suspend fun handleIncomingConnection() {
        try {
            readAndParseHttpRequest()
        } catch (e: Exception) {
            logger.error(e) { "Failed to handle HTTP proxy request from ${remoteAddress}" }
            respondToFailure("Error parsing request: ${e.message}")
            connectSessionChannel.close(e) // Close channel with error
            close()
        }
    }


    private suspend fun readAndParseHttpRequest() {
        logger.debug { "Reading HTTP request from: $remoteAddress" }
        val requestBuffer = ByteArrayOutputStream()
        val tempBuffer = ByteBuffer.allocate(1024)
        var headersEnded = false

        // Read until


        while (isOpen && !headersEnded) {
            tempBuffer.clear()
            val bytesRead = clientSocket.read(tempBuffer)
            if (bytesRead == -1) {
                logger.warn { "Client ${remoteAddress} closed connection while reading HTTP headers." }
                throw IOException("Connection closed by client during header read.")
            }
            if (bytesRead > 0) {
                tempBuffer.flip()
                val bytes = ByteArray(tempBuffer.remaining())
                tempBuffer.get(bytes)
                requestBuffer.write(bytes)

                // Check for


                val currentRequest = requestBuffer.toByteArray()
                if (currentRequest.size >= 4) {
                    var found = false
                    for (i in 0..currentRequest.size - 4) {
                        if (currentRequest[i] == '\r'.code.toByte() &&
                            currentRequest[i+1] == '\n'.code.toByte() &&
                            currentRequest[i+2] == '\r'.code.toByte() &&
                            currentRequest[i+3] == '\n'.code.toByte()) {
                            headersEnded = true
                            // TODO: Handle any body content that might have been read past headers,
                            // especially for non-CONNECT requests if supported later.
                            // For CONNECT, there should be no body with the request.
                            break
                        }
                    }
                }
            } else {
                // Potentially yield or delay if 0 bytes read but connection is open
                kotlinx.coroutines.delay(10)
            }
        }

        if (!headersEnded) {
             throw IOException("Malformed HTTP request: Headers did not end with \r\n\r\n from ${remoteAddress}")
        }

        val fullRequestString = requestBuffer.toString(StandardCharsets.US_ASCII.name())
        logger.trace { "Received HTTP Request from ${remoteAddress}:\n$fullRequestString" }

        val headersPart = fullRequestString.substringBefore("\r\n\r\n")
        val lines = headersPart.split("\r\n")

        if (lines.isEmpty()) {
            throw IOException("Malformed HTTP request: Empty request from ${remoteAddress}")
        }

        requestLine = lines[0]
        for (i in 1 until lines.size) {
            val headerLine = lines[i]
            val parts = headerLine.split(":", limit = 2)
            if (parts.size == 2) {
                httpHeaders[parts[0].trim()] = parts[1].trim()
            }
        }

        logger.debug { "Request line from ${remoteAddress}: $requestLine" }
        logger.debug { "Headers from ${remoteAddress}: $httpHeaders" }

        // Parse request line: "METHOD target HTTP/version"
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) { // Allow missing HTTP version for simplicity, though spec requires it
            throw IOException("Malformed HTTP request line: '$requestLine' from ${remoteAddress}")
        }
        val method = requestParts[0].uppercase()
        val target = requestParts[1]

        if (method != "CONNECT") {
            // For now, only CONNECT is supported.
            // Later, other methods like GET, POST for standard HTTP proxying can be added.
            logger.warn { "Unsupported HTTP method '$method' from ${remoteAddress}. Only CONNECT is supported." }
            respondWithStatusCode(405, "Method Not Allowed") // 405 Method Not Allowed
            throw IOException("Unsupported HTTP method: $method. Only CONNECT is supported.")
        }

        // For CONNECT, target is "host:port"
        val targetParts = target.split(":")
        if (targetParts.size != 2) {
            throw IOException("Malformed CONNECT target: '$target'. Expected host:port from ${remoteAddress}")
        }
        val host = targetParts[0]
        val portVal = targetParts[1].toIntOrNull()
            ?: throw IOException("Invalid port in CONNECT target: '${targetParts[1]}' from ${remoteAddress}")

        if (portVal <= 0 || portVal > 65535) {
             throw IOException("Port number out of range in CONNECT target: $portVal from ${remoteAddress}")
        }

        val session = ConnectSession(host, Port(portVal))
        logger.info { "HTTP CONNECT request from ${remoteAddress} for ${session.host}:${session.port.value}" }
        connectSessionChannel.send(session) // Send the parsed session
        connectSessionChannel.close() // Close after sending one session
    }

    override suspend fun respondToSuccess() {
        logger.debug { "Responding HTTP 200 OK to ${remoteAddress} for CONNECT request." }
        // Standard response for successful CONNECT
        val httpResponse = "HTTP/1.1 200 Connection Established\r\n\r\n"
        try {
            withContext(Dispatchers.IO) {
                val buffer = ByteBuffer.wrap(httpResponse.toByteArray(StandardCharsets.US_ASCII))
                clientSocket.write(buffer)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send HTTP 200 OK to ${remoteAddress}" }
            close() // Close connection if response fails
        }
    }

    private suspend fun respondWithStatusCode(code: Int, message: String, additionalHeaders: Map<String, String> = emptyMap()) {
        logger.debug { "Responding HTTP $code $message to ${remoteAddress}" }
        val responseBuilder = StringBuilder()
        responseBuilder.append("HTTP/1.1 $code $message\r\n")
        responseBuilder.append("Connection: close\r\n") // Usually close on error
        responseBuilder.append("Content-Length: 0\r\n") // No body for error responses typically
        additionalHeaders.forEach { (key, value) ->
            responseBuilder.append("$key: $value\r\n")
        }
        responseBuilder.append("\r\n") // End of headers

        try {
            withContext(Dispatchers.IO) {
                val buffer = ByteBuffer.wrap(responseBuilder.toString().toByteArray(StandardCharsets.US_ASCII))
                clientSocket.write(buffer)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send HTTP $code $message to ${remoteAddress}" }
        } finally {
             // Consider closing the connection after sending an error response.
            close()
        }
    }


    override suspend fun respondToFailure(reason: String) {
         // Typically send a 502 Bad Gateway or 503 Service Unavailable if the proxy failed to connect upstream.
         // Or a 400 Bad Request if the client's request was malformed (though earlier parsing should catch this).
        logger.warn { "Responding with HTTP 502 Bad Gateway to ${remoteAddress} due to: $reason" }
        respondWithStatusCode(502, "Bad Gateway")
    }

    override suspend fun read(buffer: ByteBuffer): Int {
        // This read is for data transfer *after* CONNECT is established.
        // The initial HTTP request parsing is done by readAndParseHttpRequest.
        return clientSocket.read(buffer)
    }

    override suspend fun write(buffer: ByteBuffer): Int {
        // This write is for data transfer *after* CONNECT is established.
        return clientSocket.write(buffer)
    }

    override fun close() {
        logger.debug { "Closing HttpProxySocket for ${remoteAddress}." }
        if (!connectSessionChannel.isClosedForSend) {
            connectSessionChannel.close(IOException("HttpProxySocket closed before session could be established."))
        }
        clientSocket.close()
    }
}
