package io.github.hohohahe.nekitkotlin.core

@JvmInline
value class Port(val value: Int) {
    init {
        require(value in 0..65535) { "Port number must be between 0 and 65535" }
    }
    override fun toString(): String = value.toString()
}
