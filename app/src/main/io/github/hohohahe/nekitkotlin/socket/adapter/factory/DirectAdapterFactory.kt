package io.github.hohohahe.nekitkotlin.socket.adapter.factory

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket
import io.github.hohohahe.nekitkotlin.socket.adapter.DirectAdapterSocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket

class DirectAdapterFactory : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        // Potentially pass shared resources like EventLoopGroup to NettyRawTcpSocket if optimized
        return DirectAdapterSocket(NettyRawTcpSocket())
    }
}
