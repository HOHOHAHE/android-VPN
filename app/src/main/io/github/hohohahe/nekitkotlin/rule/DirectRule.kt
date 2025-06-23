package io.github.hohohahe.nekitkotlin.rule

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.DirectAdapterFactory

/**
 * A rule that always matches and returns a DirectAdapterFactory.
 * This can be used as a default or fallback rule.
 */
class DirectRule : Rule {
    private val directAdapterFactory = DirectAdapterFactory()

    override fun match(session: ConnectSession): AdapterFactory? {
        // This rule matches any session and directs it through a DirectAdapter.
        // More specific rules would have criteria here.
        return directAdapterFactory
    }
}
