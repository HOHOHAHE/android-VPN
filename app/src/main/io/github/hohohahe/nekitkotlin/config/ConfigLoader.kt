package io.github.hohohahe.nekitkotlin.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory // Import YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.io.InputStream

object ConfigLoader {
    private val objectMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerModule( // Changed here
        KotlinModule.Builder()
            .withReflectionCacheSize(512)
            .configure(KotlinFeature.NullToEmptyCollection, true)
            .configure(KotlinFeature.NullToEmptyMap, true)
            .configure(KotlinFeature.NullIsSameAsDefault, true) 
            .configure(KotlinFeature.SingletonSupport, true)
            .configure(KotlinFeature.StrictNullChecks, false) 
            .build()
    )

    fun loadFromFile(file: File): FullNekitConfig {
        if (!file.exists()) {
            throw IllegalArgumentException("Configuration file does not exist: ${file.absolutePath}")
        }
        return objectMapper.readValue(file, FullNekitConfig::class.java)
    }

    fun loadFromStream(inputStream: InputStream): FullNekitConfig {
        return objectMapper.readValue(inputStream, FullNekitConfig::class.java)
    }
    
    fun loadFromString(yamlString: String): FullNekitConfig { // Parameter renamed
        return objectMapper.readValue(yamlString, FullNekitConfig::class.java)
    }
}
