package com.example.vpntest.hev

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * HEV Socks5 Tunnel 管理器
 * 
 * 負責管理 HEV tunnel 的生命週期、錯誤處理和效能監控
 */
class HevTunnelManager {
    
    companion object {
        private const val TAG = "HevTunnelManager"
        
        // 錯誤碼定義
        const val ERROR_NONE = 0
        const val ERROR_INVALID_CONFIG = -1
        const val ERROR_TUNNEL_INIT_FAILED = -2
        const val ERROR_NETWORK_UNAVAILABLE = -3
        const val ERROR_PERMISSION_DENIED = -4
        const val ERROR_UNKNOWN = -999
        
        init {
            try {
                System.loadLibrary("hev-tunnel-bridge")
                Log.i(TAG, "✅ HEV tunnel native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Failed to load HEV tunnel native library", e)
                throw RuntimeException("HEV Tunnel native library not available", e)
            }
        }
        
        fun getErrorMessage(errorCode: Int): String {
            return when (errorCode) {
                ERROR_NONE -> "操作成功"
                ERROR_INVALID_CONFIG -> "配置文件無效或格式錯誤"
                ERROR_TUNNEL_INIT_FAILED -> "Tunnel 初始化失敗"
                ERROR_NETWORK_UNAVAILABLE -> "網路不可用"
                ERROR_PERMISSION_DENIED -> "權限不足"
                ERROR_UNKNOWN -> "未知錯誤"
                else -> "錯誤碼: $errorCode"
            }
        }
    }
    
    // Native methods
    private external fun startTunnelNative(tunFd: Int, configPath: String): Int
    private external fun stopTunnelNative()
    private external fun isRunningNative(): Boolean
    private external fun getTunnelStatsNative(): String
    
    // 狀態管理
    private val isInitialized = AtomicBoolean(false)
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 效能監控
    private val startTimeMs = AtomicLong(0)
    private val lastErrorCode = AtomicLong(ERROR_NONE.toLong())
    private val restartCount = AtomicLong(0)
    
    /**
     * 啟動 HEV tunnel
     * 
     * @param tunFd TUN interface 檔案描述符
     * @param configPath 配置檔案路徑
     * @return 是否啟動成功
     */
    fun startTunnel(tunFd: Int, configPath: String): Boolean {
        return try {
            Log.i(TAG, "🚀 Starting HEV tunnel with fd=$tunFd, config=$configPath")
            
            // 驗證參數
            if (tunFd <= 0) {
                Log.e(TAG, "❌ Invalid TUN file descriptor: $tunFd")
                lastErrorCode.set(ERROR_PERMISSION_DENIED.toLong())
                return false
            }
            
            // 檢查配置文件
            if (configPath.isBlank()) {
                Log.e(TAG, "❌ Config path is empty")
                lastErrorCode.set(ERROR_INVALID_CONFIG.toLong())
                return false
            }
            
            startTimeMs.set(System.currentTimeMillis())
            val result = startTunnelNative(tunFd, configPath)
            lastErrorCode.set(result.toLong())
            
            if (result == ERROR_NONE) {
                isInitialized.set(true)
                Log.i(TAG, "✅ HEV tunnel started successfully")
                return true
            } else {
                val errorMsg = getErrorMessage(result)
                Log.e(TAG, "❌ Failed to start HEV tunnel: $errorMsg")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception starting HEV tunnel", e)
            lastErrorCode.set(ERROR_UNKNOWN.toLong())
            false
        }
    }
    
    /**
     * 停止 HEV tunnel
     * 
     * @return 是否停止成功
     */
    fun stopTunnel(): Boolean {
        return try {
            if (!isInitialized.get()) {
                Log.w(TAG, "⚠️ HEV tunnel not initialized")
                return true
            }
            
            Log.i(TAG, "🛑 Stopping HEV tunnel...")
            val startTime = System.currentTimeMillis()
            
            stopTunnelNative()
            isInitialized.set(false)
            
            val stopTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "✅ HEV tunnel stopped successfully (took ${stopTime}ms)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception stopping HEV tunnel", e)
            lastErrorCode.set(ERROR_UNKNOWN.toLong())
            false
        }
    }
    
    /**
     * 檢查 tunnel 是否正在運行
     * 
     * @return tunnel 運行狀態
     */
    fun isRunning(): Boolean {
        return try {
            val running = isInitialized.get() && isRunningNative()
            Log.v(TAG, "🔍 HEV tunnel running status: $running")
            running
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception checking HEV tunnel status", e)
            lastErrorCode.set(ERROR_UNKNOWN.toLong())
            false
        }
    }
    
    /**
     * 重啟 tunnel
     * 
     * @param tunFd TUN interface 檔案描述符
     * @param configPath 配置檔案路徑
     * @return 是否重啟成功
     */
    fun restartTunnel(tunFd: Int, configPath: String): Boolean {
        Log.i(TAG, "🔄 Restarting HEV tunnel...")
        restartCount.incrementAndGet()
        
        if (isRunning()) {
            stopTunnel()
            // 給一點時間讓 native 組件清理
            Thread.sleep(100)
        }
        
        return startTunnel(tunFd, configPath)
    }
    
    /**
     * 獲取 tunnel 統計資訊
     * 
     * @return 統計資訊字串
     */
    fun getStats(): String {
        return try {
            val nativeStats = if (isInitialized.get()) getTunnelStatsNative() else "N/A"
            val uptimeMs = if (startTimeMs.get() > 0) {
                System.currentTimeMillis() - startTimeMs.get()
            } else 0
            
            buildString {
                appendLine("=== HEV Tunnel Manager Stats ===")
                appendLine("📊 Status: ${if (isRunning()) "🟢 RUNNING" else "🔴 STOPPED"}")
                appendLine("⏱️ Uptime: ${uptimeMs / 1000}s")
                appendLine("🔄 Restart Count: ${restartCount.get()}")
                appendLine("❗ Last Error: ${getErrorMessage(lastErrorCode.get().toInt())}")
                appendLine("📈 Native Stats: $nativeStats")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting tunnel stats", e)
            "Error retrieving stats: ${e.message}"
        }
    }
    
    /**
     * 獲取最後的錯誤碼
     * 
     * @return 錯誤碼
     */
    fun getLastErrorCode(): Int {
        return lastErrorCode.get().toInt()
    }
    
    /**
     * 清理資源
     */
    fun cleanup() {
        Log.i(TAG, "🧹 Cleaning up HEV tunnel manager...")
        stopTunnel()
        managerScope.cancel()
        Log.i(TAG, "✅ HEV tunnel manager cleanup completed")
    }
}