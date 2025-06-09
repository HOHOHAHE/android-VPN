package com.example.vpntest.migration

import android.util.Log

/**
 * 遷移標誌管理器
 * 
 * 負責控制 HEV Socks5 Tunnel 遷移過程中的功能開關和配置
 */
object MigrationFlags {
    
    private const val TAG = "MigrationFlags"
    
    // 主要遷移標誌
    const val USE_HEV_TUNNEL = true
    const val KEEP_LEGACY_COMPONENTS = false
    const val ENABLE_PERFORMANCE_MONITORING = true
    const val ENABLE_DETAILED_LOGGING = true
    
    // 測試和除錯標誌
    const val ENABLE_INTEGRATION_TESTS = true
    const val ENABLE_MIGRATION_VALIDATION = true
    const val ENABLE_FALLBACK_MODE = true
    
    // 效能調整標誌
    const val OPTIMIZE_MEMORY_USAGE = true
    const val ENABLE_JNI_OPTIMIZATION = true
    const val USE_ASYNC_OPERATIONS = true
    
    // 安全和權限標誌
    const val ENFORCE_STRICT_VALIDATION = true
    const val ENABLE_ERROR_RECOVERY = true
    const val LOG_SENSITIVE_DATA = false // 生產環境應為 false
    
    /**
     * 檢查是否應該使用舊版處理器
     */
    fun shouldUseLegacyProcessor(): Boolean {
        val result = !USE_HEV_TUNNEL || (isDebugMode() && KEEP_LEGACY_COMPONENTS)
        Log.d(TAG, "🔍 Should use legacy processor: $result")
        return result
    }
    
    /**
     * 檢查是否應該使用 HEV Tunnel
     */
    fun shouldUseHevTunnel(): Boolean {
        val result = USE_HEV_TUNNEL
        Log.d(TAG, "🔍 Should use HEV tunnel: $result")
        return result
    }
    
    /**
     * 檢查是否為除錯模式
     */
    fun isDebugMode(): Boolean {
        // 使用系統屬性判斷，避免 BuildConfig 依賴問題
        val debugProperty = System.getProperty("debug", "false").toBoolean()
        val result = debugProperty || Log.isLoggable(TAG, Log.DEBUG)
        Log.v(TAG, "🔍 Debug mode: $result")
        return result
    }
    
    /**
     * 檢查是否啟用效能監控
     */
    fun isPerformanceMonitoringEnabled(): Boolean {
        return ENABLE_PERFORMANCE_MONITORING && (isDebugMode() || USE_HEV_TUNNEL)
    }
    
    /**
     * 檢查是否啟用詳細日誌
     */
    fun isDetailedLoggingEnabled(): Boolean {
        return ENABLE_DETAILED_LOGGING || isDebugMode()
    }
    
    /**
     * 檢查是否啟用集成測試
     */
    fun isIntegrationTestsEnabled(): Boolean {
        return ENABLE_INTEGRATION_TESTS && isDebugMode()
    }
    
    /**
     * 檢查是否啟用遷移驗證
     */
    fun isMigrationValidationEnabled(): Boolean {
        return ENABLE_MIGRATION_VALIDATION
    }
    
    /**
     * 檢查是否啟用備用模式
     */
    fun isFallbackModeEnabled(): Boolean {
        return ENABLE_FALLBACK_MODE
    }
    
    /**
     * 檢查是否啟用記憶體最佳化
     */
    fun isMemoryOptimizationEnabled(): Boolean {
        return OPTIMIZE_MEMORY_USAGE
    }
    
    /**
     * 檢查是否啟用 JNI 最佳化
     */
    fun isJniOptimizationEnabled(): Boolean {
        return ENABLE_JNI_OPTIMIZATION && USE_HEV_TUNNEL
    }
    
    /**
     * 檢查是否使用非同步操作
     */
    fun isAsyncOperationsEnabled(): Boolean {
        return USE_ASYNC_OPERATIONS
    }
    
    /**
     * 檢查是否啟用嚴格驗證
     */
    fun isStrictValidationEnabled(): Boolean {
        return ENFORCE_STRICT_VALIDATION
    }
    
    /**
     * 檢查是否啟用錯誤恢復
     */
    fun isErrorRecoveryEnabled(): Boolean {
        return ENABLE_ERROR_RECOVERY
    }
    
    /**
     * 檢查是否記錄敏感資料
     */
    fun shouldLogSensitiveData(): Boolean {
        return LOG_SENSITIVE_DATA && isDebugMode()
    }
    
    /**
     * 獲取當前遷移階段
     */
    fun getCurrentMigrationPhase(): MigrationPhase {
        return when {
            !USE_HEV_TUNNEL -> MigrationPhase.LEGACY_ONLY
            KEEP_LEGACY_COMPONENTS -> MigrationPhase.HYBRID_MODE
            else -> MigrationPhase.HEV_ONLY
        }
    }
    
    /**
     * 獲取遷移資訊
     */
    fun getMigrationInfo(): String {
        return buildString {
            appendLine("=== Migration Flags Status ===")
            appendLine("🏗️ Migration Phase: ${getCurrentMigrationPhase()}")
            appendLine("🔧 Use HEV Tunnel: $USE_HEV_TUNNEL")
            appendLine("🔄 Keep Legacy Components: $KEEP_LEGACY_COMPONENTS")
            appendLine("🔍 Should Use Legacy Processor: ${shouldUseLegacyProcessor()}")
            appendLine("🐛 Debug Mode: ${isDebugMode()}")
            appendLine()
            appendLine("=== Feature Flags ===")
            appendLine("📊 Performance Monitoring: ${isPerformanceMonitoringEnabled()}")
            appendLine("📝 Detailed Logging: ${isDetailedLoggingEnabled()}")
            appendLine("🧪 Integration Tests: ${isIntegrationTestsEnabled()}")
            appendLine("✅ Migration Validation: ${isMigrationValidationEnabled()}")
            appendLine("🔄 Fallback Mode: ${isFallbackModeEnabled()}")
            appendLine()
            appendLine("=== Optimization Flags ===")
            appendLine("🧠 Memory Optimization: ${isMemoryOptimizationEnabled()}")
            appendLine("⚡ JNI Optimization: ${isJniOptimizationEnabled()}")
            appendLine("🔄 Async Operations: ${isAsyncOperationsEnabled()}")
            appendLine()
            appendLine("=== Security Flags ===")
            appendLine("🔒 Strict Validation: ${isStrictValidationEnabled()}")
            appendLine("🛟 Error Recovery: ${isErrorRecoveryEnabled()}")
            appendLine("🔐 Log Sensitive Data: ${shouldLogSensitiveData()}")
        }
    }
    
    /**
     * 獲取完整的系統狀態報告
     */
    fun getSystemStatusReport(): String {
        return buildString {
            appendLine("=== HEV Migration System Status ===")
            appendLine("⏰ Report Time: ${java.util.Date()}")
            appendLine()
            append(getMigrationInfo())
            appendLine()
            appendLine("=== Runtime Environment ===")
            appendLine("☕ Java Version: ${System.getProperty("java.version")}")
            appendLine("🤖 Android API: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("💾 Available Memory: ${Runtime.getRuntime().freeMemory() / 1024 / 1024}MB")
            appendLine("🏭 Processor Count: ${Runtime.getRuntime().availableProcessors()}")
        }
    }
    
    /**
     * 驗證遷移標誌的一致性
     */
    fun validateMigrationFlags(): ValidationResult {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // 檢查邏輯一致性
        if (USE_HEV_TUNNEL && shouldUseLegacyProcessor()) {
            warnings.add("同時啟用 HEV Tunnel 和 Legacy Processor，可能是除錯模式")
        }
        
        if (!USE_HEV_TUNNEL && !KEEP_LEGACY_COMPONENTS) {
            issues.add("關閉 HEV Tunnel 但不保留舊組件，系統將無法運作")
        }
        
        if (LOG_SENSITIVE_DATA && !isDebugMode()) {
            issues.add("在非除錯模式下啟用敏感資料記錄存在安全風險")
        }
        
        if (ENABLE_INTEGRATION_TESTS && !isDebugMode()) {
            warnings.add("在非除錯模式下啟用集成測試可能影響效能")
        }
        
        // 檢查相依性
        if (ENABLE_JNI_OPTIMIZATION && !USE_HEV_TUNNEL) {
            warnings.add("JNI 最佳化需要 HEV Tunnel 才能生效")
        }
        
        if (ENABLE_PERFORMANCE_MONITORING && !ENABLE_DETAILED_LOGGING) {
            warnings.add("效能監控建議配合詳細日誌使用")
        }
        
        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            warnings = warnings
        )
    }
    
    /**
     * 記錄當前配置狀態
     */
    fun logCurrentConfiguration() {
        Log.i(TAG, "🚀 HEV Migration Configuration Loaded")
        Log.i(TAG, "📊 Phase: ${getCurrentMigrationPhase()}")
        
        if (isDetailedLoggingEnabled()) {
            getMigrationInfo().lines().forEach { line ->
                if (line.isNotBlank()) Log.d(TAG, line)
            }
        }
        
        // 驗證配置
        val validation = validateMigrationFlags()
        if (!validation.isValid) {
            Log.e(TAG, "❌ Configuration validation failed:")
            validation.issues.forEach { Log.e(TAG, "  • $it") }
        }
        
        if (validation.warnings.isNotEmpty()) {
            Log.w(TAG, "⚠️ Configuration warnings:")
            validation.warnings.forEach { Log.w(TAG, "  • $it") }
        }
    }
}

/**
 * 遷移階段枚舉
 */
enum class MigrationPhase {
    LEGACY_ONLY,    // 僅使用舊架構
    HYBRID_MODE,    // 混合模式（新舊並存）
    HEV_ONLY       // 僅使用 HEV 架構
}

/**
 * 驗證結果數據類
 */
data class ValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val warnings: List<String>
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
    val hasIssues: Boolean get() = issues.isNotEmpty()
}