package io.github.hohohahe.nekitkotlin.socket.proxy

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.Socket
import kotlinx.coroutines.flow.Flow // Using Flow to emit ConnectSession
import java.nio.ByteBuffer

interface ProxySocket : Socket {
    // Flow to emit the ConnectSession once parsed.
    // Could emit null or throw an exception on the Flow for parsing errors.
    // Or use a sealed class result: Flow<Result<ConnectSession>>
    fun getConnectSession(): Flow<ConnectSession>

    suspend fun respondToSuccess() // Method to send success response to client (e.g., HTTP 200 OK)
    suspend fun respondToFailure(reason: String = "Connection failed") // Method to send failure response

    // Expose read/write for Tunnel to use
    suspend fun read(buffer: ByteBuffer): Int
    suspend fun write(buffer: ByteBuffer): Int
}
