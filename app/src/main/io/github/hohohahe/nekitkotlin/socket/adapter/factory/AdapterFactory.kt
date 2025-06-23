package io.github.hohohahe.nekitkotlin.socket.adapter.factory

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket

interface AdapterFactory {
    /**
     * Creates an AdapterSocket for the given ConnectSession.
     * @param session The ConnectSession for which to create the adapter.
     * @return An AdapterSocket instance.
     */
    fun getAdapter(session: ConnectSession): AdapterSocket
}
