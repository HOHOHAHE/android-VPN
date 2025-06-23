package io.github.hohohahe.nekitkotlin.socket.adapter

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
// Removed Socket import as AdapterSocket now extends it
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket // Default implementation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mu.KotlinLogging
import java.nio.ByteBuffer

private val logger = KotlinLogging.logger {}

// AdapterSocket interface is now in AdapterSocket.kt

class DirectAdapterSocket(
    private val rawTcpSocket: RawTcpSocket = NettyRawTcpSocket() // Inject or use default
) : AdapterSocket {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    override val isOpen: Boolean
        get() = rawTcpSocket.isOpen

    override val localAddress: IpAddress?
        get() = rawTcpSocket.localAddress

    override val remoteAddress: IpAddress?
        get() = rawTcpSocket.remoteAddress

    private var internalSession: ConnectSession? = null

    override suspend fun openSocket(session: ConnectSession): RawTcpSocket {
        if (isOpen) {
            logger.warn { "Adapter socket is already open." }
            // Potentially throw an error or return current socket if session matches
            if (this.internalSession == session && rawTcpSocket.isOpen) return rawTcpSocket
            // else close and reopen? For now, let's assume it's an error or needs explicit close first.
            throw IllegalStateException("Socket already open with a different session or state.")
        }
        this.internalSession = session
        logger.debug { "DirectAdapter opening connection to ${session.host}:${session.port.value}" }
        try {
            // Resolve DNS if necessary (ConnectSession might not have remoteAddress yet)
            // For a direct adapter, host in ConnectSession is the destination.
            rawTcpSocket.connect(session.host, session.port)
            session.remoteAddress = rawTcpSocket.remoteAddress // Update session with actual remote IP
            session.localAddress = rawTcpSocket.localAddress   // Update session with actual local IP
            _isReady.value = true
            logger.info { "DirectAdapter connected to ${session.host}:${session.port.value} (${session.remoteAddress})" }
        } catch (e: Exception) {
            _isReady.value = false
            logger.error(e) { "Failed to connect directly to ${session.host}:${session.port.value}" }
            close() // Ensure resources are cleaned up on failure
            throw e // Re-throw to signal failure
        }
        return rawTcpSocket
    }

    override suspend fun read(buffer: ByteBuffer): Int {
        if (!_isReady.value || !rawTcpSocket.isOpen) {
            logger.warn { "Attempting to read from a non-ready or closed DirectAdapterSocket." }
            return -1 // Or throw exception
        }
        return rawTcpSocket.read(buffer)
    }

    override suspend fun write(buffer: ByteBuffer): Int {
        if (!_isReady.value || !rawTcpSocket.isOpen) {
            logger.warn { "Attempting to write to a non-ready or closed DirectAdapterSocket." }
            throw IllegalStateException("Socket is not ready or closed for writing.")
        }
        return rawTcpSocket.write(buffer)
    }

    override fun close() {
        logger.debug { "Closing DirectAdapterSocket." }
        _isReady.value = false
        if (rawTcpSocket.isOpen) {
            rawTcpSocket.close()
        }
    }
}
