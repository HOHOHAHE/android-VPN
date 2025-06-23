package io.github.hohohahe.nekitkotlin.socket.adapter.factory

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket
import io.github.hohohahe.nekitkotlin.socket.adapter.HttpAdapterSocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket

class HttpAdapterFactory(
    private val proxyHost: String,
    private val proxyPort: Port
    // Add auth details if HttpAdapterSocket supports them
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        // Potentially pass shared resources like EventLoopGroup to NettyRawTcpSocket
        return HttpAdapterSocket(proxyHost, proxyPort, NettyRawTcpSocket())
    }
}
