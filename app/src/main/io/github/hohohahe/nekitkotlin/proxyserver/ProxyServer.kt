package io.github.hohohahe.nekitkotlin.proxyserver

import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.core.IpAddress

interface ProxyServer {
    val port: Port
    val host: IpAddress? // Optional: specific host to bind to

    /**
     * Starts the proxy server. This method should be suspending if startup
     * involves asynchronous operations (like binding to a port).
     * It should complete when the server is successfully started and listening.
     */
    suspend fun start()

    /**
     * Stops the proxy server and releases resources.
     */
    fun stop()

    /**
     * Returns true if the server is currently running and listening for connections.
     */
    fun isRunning(): Boolean
}
