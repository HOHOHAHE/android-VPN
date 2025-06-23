package io.github.hohohahe.nekitkotlin.core

// Using String for simplicity, can be enhanced later with validation or specific types for IPv4/IPv6
@JvmInline
value class IpAddress(val value: String) {
    // Basic validation: Not empty. Could be extended for proper IP format.
    init {
        require(value.isNotBlank()) { "IP address cannot be blank" }
    }
    override fun toString(): String = value
}
