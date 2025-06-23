package io.github.hohohahe.nekitkotlin.rule

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.DirectAdapterFactory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class RuleManager(
    private val rules: List<Rule>,
    private val defaultAdapterFactory: AdapterFactory = DirectAdapterFactory() // Default if no rules match
) {
    fun match(session: ConnectSession): AdapterFactory {
        for (rule in rules) {
            val factory = rule.match(session)
            if (factory != null) {
                logger.debug { "Session for ${session.host}:${session.port} matched by rule: ${rule::class.simpleName}. Using factory: ${factory::class.simpleName}" }
                return factory
            }
        }
        logger.debug { "No specific rule matched for ${session.host}:${session.port}. Using default factory: ${defaultAdapterFactory::class.simpleName}" }
        return defaultAdapterFactory
    }
}
