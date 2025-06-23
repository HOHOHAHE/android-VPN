package io.github.hohohahe.nekitkotlin.rule

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory

interface Rule {
    /**
     * Matches the given ConnectSession against the rule.
     * @param session The ConnectSession to evaluate.
     * @return An AdapterFactory if the rule matches, otherwise null.
     */
    fun match(session: ConnectSession): AdapterFactory?
}
