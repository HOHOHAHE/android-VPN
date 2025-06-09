package com.example.vpntest.hev

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * HEV Tunnel 監控器
 * 
 * 負責監控 tunnel 狀態、處理異常重啟和效能統計
 */
class TunnelMonitor(private val hevTunnelManager: HevTunnelManager) {
    
    companion object {
        private const val TAG = "TunnelMonitor"
        private const val MONITOR_INTERVAL_MS = 5000L
        private const val HEALTH_CHECK_INTERVAL_MS = 10000L
        private const val MAX_RESTART_ATTEMPTS = 3
        private const val RESTART_COOLDOWN_MS = 30000L
    }
    
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var healthCheckJob: Job? = null
    
    private val _status = MutableStateFlow(TunnelStatus.STOPPED)
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    
    // 統計資訊
    private val statusChangeCount = AtomicLong(0)
    private val crashCount = AtomicLong(0)
    private val restartAttempts = AtomicLong(0)
    private val lastRestartTime = AtomicLong(0)
    private val monitorStartTime = AtomicLong(0)
    
    private var restartCallback: (suspend () -> Boolean)? = null
    
    /**
     * 設定重啟回調函數
     */
    fun setRestartCallback(callback: suspend () -> Boolean) {
        restartCallback = callback
        Log.d(TAG, "🔄 Restart callback configured")
    }
    
    /**
     * 開始監控
     */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "⚠️ Monitor already running")
            return
        }
        
        Log.i(TAG, "🔍 Starting HEV tunnel monitor")
        monitorStartTime.set(System.currentTimeMillis())
        
        monitorJob = monitorScope.launch {
            updateStatus(TunnelStatus.MONITORING)
            
            while (isActive) {
                try {
                    performStatusCheck()
                    delay(MONITOR_INTERVAL_MS)
                } catch (e: CancellationException) {
                    Log.d(TAG, "🛑 Monitor cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Monitor error", e)
                    delay(1000) // 錯誤時短暫延遲
                }
            }
        }
        
        // 啟動健康檢查
        startHealthCheck()
    }
    
    /**
     * 停止監控
     */
    fun stopMonitoring() {
        Log.i(TAG, "🛑 Stopping HEV tunnel monitor")
        
        monitorJob?.cancel()
        monitorJob = null
        
        healthCheckJob?.cancel()
        healthCheckJob = null
        
        updateStatus(TunnelStatus.STOPPED)
        
        val uptime = System.currentTimeMillis() - monitorStartTime.get()
        Log.i(TAG, "✅ Monitor stopped (uptime: ${uptime / 1000}s)")
    }
    
    /**
     * 執行狀態檢查
     */
    private suspend fun performStatusCheck() {
        val isRunning = hevTunnelManager.isRunning()
        val currentStatus = _status.value
        
        Log.v(TAG, "🔍 Status check: running=$isRunning, current=$currentStatus")
        
        when (currentStatus) {
            TunnelStatus.MONITORING -> {
                if (isRunning) {
                    updateStatus(TunnelStatus.RUNNING)
                    Log.i(TAG, "✅ Tunnel detected as running")
                }
            }
            
            TunnelStatus.RUNNING -> {
                if (!isRunning) {
                    Log.w(TAG, "💥 Tunnel process died unexpectedly")
                    crashCount.incrementAndGet()
                    updateStatus(TunnelStatus.CRASHED)
                    handleTunnelCrash()
                }
            }
            
            TunnelStatus.RESTARTING -> {
                if (isRunning) {
                    updateStatus(TunnelStatus.RUNNING)
                    Log.i(TAG, "✅ Tunnel restart successful")
                    restartAttempts.set(0) // 重置重啟計數
                }
            }
            
            else -> {
                // 其他狀態不需要特殊處理
            }
        }
    }
    
    /**
     * 啟動健康檢查
     */
    private fun startHealthCheck() {
        healthCheckJob = monitorScope.launch {
            while (isActive) {
                try {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                    performHealthCheck()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Health check error", e)
                }
            }
        }
    }
    
    /**
     * 執行健康檢查
     */
    private suspend fun performHealthCheck() {
        if (_status.value == TunnelStatus.RUNNING) {
            try {
                // 檢查錯誤碼
                val errorCode = hevTunnelManager.getLastErrorCode()
                if (errorCode != HevTunnelManager.ERROR_NONE) {
                    Log.w(TAG, "⚠️ Tunnel health check failed: ${HevTunnelManager.getErrorMessage(errorCode)}")
                }
                
                // 獲取統計資訊
                val stats = hevTunnelManager.getStats()
                Log.v(TAG, "📊 Health check: $stats")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Health check exception", e)
            }
        }
    }
    
    /**
     * 處理 tunnel 崩潰
     */
    private suspend fun handleTunnelCrash() {
        val attempts = restartAttempts.incrementAndGet()
        val currentTime = System.currentTimeMillis()
        val timeSinceLastRestart = currentTime - lastRestartTime.get()
        
        Log.w(TAG, "💥 Handling tunnel crash (attempt $attempts/$MAX_RESTART_ATTEMPTS)")
        
        // 檢查重啟冷卻時間
        if (timeSinceLastRestart < RESTART_COOLDOWN_MS) {
            Log.w(TAG, "⏳ Restart cooldown active, waiting...")
            updateStatus(TunnelStatus.WAITING)
            delay(RESTART_COOLDOWN_MS - timeSinceLastRestart)
        }
        
        // 檢查最大重啟次數
        if (attempts > MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "❌ Max restart attempts exceeded, giving up")
            updateStatus(TunnelStatus.FAILED)
            return
        }
        
        try {
            updateStatus(TunnelStatus.RESTARTING)
            lastRestartTime.set(currentTime)
            
            Log.i(TAG, "🔄 Attempting tunnel restart...")
            val success = restartCallback?.invoke() ?: false
            
            if (success) {
                Log.i(TAG, "✅ Tunnel restart initiated successfully")
                // 狀態會在下一次檢查時更新為 RUNNING
            } else {
                Log.e(TAG, "❌ Tunnel restart failed")
                updateStatus(TunnelStatus.FAILED)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during tunnel restart", e)
            updateStatus(TunnelStatus.FAILED)
        }
    }
    
    /**
     * 更新狀態
     */
    private fun updateStatus(newStatus: TunnelStatus) {
        val oldStatus = _status.value
        if (oldStatus != newStatus) {
            _status.value = newStatus
            statusChangeCount.incrementAndGet()
            Log.i(TAG, "🔄 Status changed: $oldStatus → $newStatus")
        }
    }
    
    /**
     * 強制重啟
     */
    suspend fun forceRestart(): Boolean {
        Log.i(TAG, "🔧 Force restart requested")
        restartAttempts.set(0) // 重置計數器
        updateStatus(TunnelStatus.CRASHED)
        handleTunnelCrash()
        return _status.value == TunnelStatus.RUNNING
    }
    
    /**
     * 獲取監控統計
     */
    fun getMonitorStats(): String {
        val uptime = System.currentTimeMillis() - monitorStartTime.get()
        return buildString {
            appendLine("=== Tunnel Monitor Stats ===")
            appendLine("📊 Current Status: ${_status.value}")
            appendLine("⏱️ Monitor Uptime: ${uptime / 1000}s")
            appendLine("🔄 Status Changes: ${statusChangeCount.get()}")
            appendLine("💥 Crash Count: ${crashCount.get()}")
            appendLine("🔄 Restart Attempts: ${restartAttempts.get()}")
            appendLine("⏰ Last Restart: ${if (lastRestartTime.get() > 0) "${(System.currentTimeMillis() - lastRestartTime.get()) / 1000}s ago" else "Never"}")
        }
    }
    
    /**
     * 清理資源
     */
    fun cleanup() {
        Log.i(TAG, "🧹 Cleaning up tunnel monitor...")
        stopMonitoring()
        monitorScope.cancel()
        Log.i(TAG, "✅ Tunnel monitor cleanup completed")
    }
}

/**
 * Tunnel 狀態枚舉
 */
enum class TunnelStatus {
    STOPPED,        // 已停止
    MONITORING,     // 監控中
    RUNNING,        // 運行中
    CRASHED,        // 已崩潰
    RESTARTING,     // 重啟中
    WAITING,        // 等待中（冷卻期）
    FAILED          // 失敗
}