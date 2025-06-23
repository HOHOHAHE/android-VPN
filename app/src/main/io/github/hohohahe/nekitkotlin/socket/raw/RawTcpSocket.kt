package io.github.hohohahe.nekitkotlin.socket.raw

import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import java.nio.ByteBuffer

interface RawTcpSocket {
    val isOpen: Boolean
    val localAddress: IpAddress?
    val remoteAddress: IpAddress?

    suspend fun connect(host: String, port: Port)
    suspend fun read(buffer: ByteBuffer): Int // Returns bytes read or -1 for EOF
    suspend fun write(buffer: ByteBuffer): Int // Returns bytes written
    fun close()
}
