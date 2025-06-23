package io.github.hohohahe.nekitkotlin.socket.proxy

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import mu.KotlinLogging
import java.nio.ByteBuffer

private val logger = KotlinLogging.logger {}

class DummyProxySocket(
    private val targetSession: ConnectSession,
    private val underlyingSocket: RawTcpSocket // Represents the client connection
) : ProxySocket {

    override val isOpen: Boolean get() = underlyingSocket.isOpen
    override val localAddress: IpAddress? get() = underlyingSocket.localAddress
    override val remoteAddress: IpAddress? get() = underlyingSocket.remoteAddress // Client's address

    override fun getConnectSession(): Flow<ConnectSession> {
        logger.debug { "DummyProxySocket providing session: $targetSession" }
        return flowOf(targetSession)
    }

    override suspend fun respondToSuccess() {
        logger.info { "DummyProxySocket: Responding with SUCCESS to client (${remoteAddress})" }
        // In a real scenario, this would write protocol-specific success bytes to underlyingSocket
        // For a dummy, we can just log. If the underlyingSocket is a mock, it might expect calls.
    }

    override suspend fun respondToFailure(reason: String) {
        logger.warn { "DummyProxySocket: Responding with FAILURE to client (${remoteAddress}): $reason" }
        // Protocol-specific failure bytes
    }

    override suspend fun read(buffer: ByteBuffer): Int {
        // Simulate reading from the client (e.g., for data forwarding phase)
        if (!underlyingSocket.isOpen) return -1
        // This dummy won't actually have a live client sending data unless underlyingSocket is real.
        // For testing Tunnel, we might not need this to do much if we focus on connection setup.
        // Return 0 to indicate no data for now, or -1 if closed.
        // logger.trace { "DummyProxySocket read called, returning 0 (no data)" }
        // return 0
        return underlyingSocket.read(buffer) // Delegate if underlyingSocket is real
    }

    override suspend fun write(buffer: ByteBuffer): Int {
        // Simulate writing to the client (e.g., for data forwarding phase)
        if (!underlyingSocket.isOpen) throw  IllegalStateException("Socket closed")
        // logger.trace { "DummyProxySocket write called with ${buffer.remaining()} bytes" }
        // return buffer.remaining() // Pretend all bytes are written
        return underlyingSocket.write(buffer) // Delegate
    }

    override fun close() {
        logger.debug { "DummyProxySocket closing." }
        if (underlyingSocket.isOpen) {
            underlyingSocket.close()
        }
    }
}
