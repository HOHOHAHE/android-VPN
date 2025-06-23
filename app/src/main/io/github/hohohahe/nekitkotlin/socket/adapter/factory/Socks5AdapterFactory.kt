package io.github.hohohahe.nekitkotlin.socket.adapter.factory

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket
import io.github.hohohahe.nekitkotlin.socket.adapter.Socks5AdapterSocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket

class Socks5AdapterFactory(
    private val proxyHost: String,
    private val proxyPort: Port
    // Add auth details if Socks5AdapterSocket supports them
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return Socks5AdapterSocket(proxyHost, proxyPort, NettyRawTcpSocket())
    }
}
