package io.github.hohohahe.nekitkotlin.core

data class ConnectSession(
    val host: String, // The requested hostname or IP address string
    val port: Port,
    var remoteAddress: IpAddress? = null, // Resolved IP address
    var localAddress: IpAddress? = null,  // Local address used for the connection
    // Potentially other metadata like requested network interface, etc.
)
