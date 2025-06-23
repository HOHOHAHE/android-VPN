package io.github.hohohahe.nekitkotlin.rule

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A rule that matches all connections and directs them to the specified adapter factory.
 * Useful as a final or default rule.
 */
class AllRule(private val adapterFactory: AdapterFactory) : Rule {
    override fun match(session: ConnectSession): AdapterFactory? {
        logger.debug { "AllRule matched for session ${session.host}:${session.port}. Using adapter: ${adapterFactory::class.simpleName}" }
        return adapterFactory
    }
}
