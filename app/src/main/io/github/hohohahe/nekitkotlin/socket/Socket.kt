package io.github.hohohahe.nekitkotlin.socket

import io.github.hohohahe.nekitkotlin.core.IpAddress
import java.nio.ByteBuffer

interface Socket {
    val isOpen: Boolean
    val localAddress: IpAddress?
    val remoteAddress: IpAddress? // For AdapterSocket, this is the destination. For ProxySocket, this is the client.

    // Consider if read/write are needed directly on this base interface
    // or if they are specific to ProxySocket/AdapterSocket roles after connection.
    // For now, let's assume data transfer is handled by Tunnel after setup.
    // suspend fun read(buffer: ByteBuffer): Int
    // suspend fun write(buffer: ByteBuffer): Int

    fun close()
}
