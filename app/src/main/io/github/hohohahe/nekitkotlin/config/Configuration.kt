package io.github.hohohahe.nekitkotlin.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.proxyserver.ProxyType

data class ServerConfig(
    val port: Int, 
    val type: ProxyType, 
    val host: String? = "0.0.0.0" 
) {
    fun getPort(): Port = Port(port)
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = RuleConfig.DirectRuleConfig::class, name = "direct"),
    JsonSubTypes.Type(value = RuleConfig.DomainRuleConfig::class, name = "domain"),
    JsonSubTypes.Type(value = RuleConfig.CountryRuleConfig::class, name = "country"), 
    JsonSubTypes.Type(value = RuleConfig.AllRuleConfig::class, name = "all")
)
sealed class RuleConfig {
    abstract val adapter: String 
    data class DirectRuleConfig(@JsonProperty("adapter") override val adapter: String = "direct") : RuleConfig()
    data class DomainRuleConfig(val domains: List<String> = emptyList(), val matcher: DomainMatcherType = DomainMatcherType.KEYWORD, override val adapter: String) : RuleConfig()
    enum class DomainMatcherType { KEYWORD, SUFFIX, REGEX }
    data class CountryRuleConfig(val countryCode: String, override val adapter: String) : RuleConfig()
    data class AllRuleConfig(@JsonProperty("adapter") override val adapter: String = "direct") : RuleConfig()
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AdapterFactoryConfig.DirectAdapterFactoryConfig::class, name = "direct"),
    JsonSubTypes.Type(value = AdapterFactoryConfig.HttpAdapterFactoryConfig::class, name = "http-proxy"),
    JsonSubTypes.Type(value = AdapterFactoryConfig.Socks5AdapterFactoryConfig::class, name = "socks5-proxy")
)
sealed class AdapterFactoryConfig {
    abstract val name: String 
    data class DirectAdapterFactoryConfig(@JsonProperty("name") override val name: String = "direct") : AdapterFactoryConfig()
    data class HttpAdapterFactoryConfig(@JsonProperty("name") override val name: String, val host: String, val port: Int) : AdapterFactoryConfig()
    data class Socks5AdapterFactoryConfig(@JsonProperty("name") override val name: String, val host: String, val port: Int) : AdapterFactoryConfig()
}

data class FullNekitConfig(
    val servers: List<ServerConfig> = emptyList(),
    @JsonProperty("adapter-factories") 
    val adapterFactories: List<AdapterFactoryConfig> = listOf(AdapterFactoryConfig.DirectAdapterFactoryConfig()), 
    val rules: List<RuleConfig> = emptyList()
)
