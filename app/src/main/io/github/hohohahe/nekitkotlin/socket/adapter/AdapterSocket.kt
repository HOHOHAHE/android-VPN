package io.github.hohohahe.nekitkotlin.socket.adapter

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.Socket
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

interface AdapterSocket : Socket {
    val isReady: StateFlow<Boolean>
    /**
     * Opens the connection to the destination specified in the session,
     * potentially via an upstream proxy defined by the adapter's implementation.
     *
     * @param session The details of the connection to establish.
     * @return The underlying RawTcpSocket used for the final connection.
     * @throws Exception if the connection or handshake fails.
     */
    suspend fun openSocket(session: ConnectSession): RawTcpSocket

    // Methods for data transfer after connection is established and ready
    suspend fun read(buffer: ByteBuffer): Int
    suspend fun write(buffer: ByteBuffer): Int
}
