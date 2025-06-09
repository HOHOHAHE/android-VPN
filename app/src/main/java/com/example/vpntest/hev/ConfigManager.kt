package com.example.vpntest.hev

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConfigManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ConfigManager"
        private const val CONFIG_DIR = "hev-tunnel"
        private const val CONFIG_FILE = "tunnel.yaml"
        private const val LOG_FILE = "hev-tunnel.log"
    }
    
    private val configDir = File(context.filesDir, CONFIG_DIR)
    private val configFile = File(configDir, CONFIG_FILE)
    private val logFile = File(context.filesDir, LOG_FILE)
    
    init {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
    }
    
    suspend fun generateConfig(
        socks5Port: Int = 1080,
        configType: ConfigType = ConfigType.DEFAULT
    ): String = withContext(Dispatchers.IO) {
        val config = when (configType) {
            ConfigType.DEFAULT -> HevTunnelConfig.createDefault()
            ConfigType.PERFORMANCE -> HevTunnelConfig.createPerformanceOptimized()
        }.copy(
            socks5 = HevTunnelConfig.Socks5Config(port = socks5Port),
            misc = HevTunnelConfig.MiscConfig(
                log_file = logFile.absolutePath,
                log_level = "warn"
            )
        )
        
        val yamlContent = config.toYamlString()
        configFile.writeText(yamlContent)
        
        Log.d(TAG, "Generated config file: ${configFile.absolutePath}")
        configFile.absolutePath
    }
    
    fun getConfigPath(): String = configFile.absolutePath
    fun getLogPath(): String = logFile.absolutePath
    
    suspend fun readLog(maxLines: Int = 100): List<String> = withContext(Dispatchers.IO) {
        try {
            if (logFile.exists()) {
                logFile.readLines().takeLast(maxLines)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log file", e)
            emptyList()
        }
    }
    
    fun clearLog() {
        try {
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log file", e)
        }
    }
    
    enum class ConfigType {
        DEFAULT,
        PERFORMANCE
    }
}