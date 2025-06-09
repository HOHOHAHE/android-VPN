# hev-socks5-tunnel 遷移實作計畫

> **專案目標**：將現有的 Android VPN 專案從自行實作的封包處理架構遷移到使用 hev-socks5-tunnel 的方案
> 
> **文件版本**：v1.0  
> **建立日期**：2024-12-01  
> **預計完成**：2025-01-15

---

## 📋 目錄

1. [專案概述](#專案概述)
2. [現有架構分析](#現有架構分析)
3. [遷移總體規劃](#遷移總體規劃)
4. [詳細實作步驟](#詳細實作步驟)
5. [風險管理與部署](#風險管理與部署)

---

## 🎯 專案概述

### 遷移目標

將現有複雜的封包處理邏輯替換為成熟的 hev-socks5-tunnel 解決方案，以：

- **降低維護成本**：移除自行實作的 TCP/UDP 處理邏輯
- **提高穩定性**：使用經過驗證的開源實作
- **保持功能完整性**：保留路由決策和代理功能
- **改善效能**：利用 native 實作的效能優勢

### 核心變更架構

```
現有架構：
Android App → VpnPacketProcessor → TcpHandler → SOCKS5 Client → Socks5Proxy → Internet

目標架構：
Android App → hev-socks5-tunnel → Socks5Proxy → Internet
```

---

## 🔍 現有架構分析

### 需要移除的組件

| 組件 | 功能 | 移除原因 |
|------|------|----------|
| `VpnPacketProcessor` | 封包解析和分發 | 由 hev-socks5-tunnel 取代 |
| `TcpHandler` | TCP 協議處理 | 由 hev-socks5-tunnel 取代 |
| IP 封包解析邏輯 | 協議識別和路由 | 不再需要應用層處理 |

### 需要保留的組件

| 組件 | 功能 | 保留原因 |
|------|------|----------|
| `ZyxelVpnService` | 服務容器 | 需要管理 hev-tunnel 子程序 |
| `VpnNetworkManager` | TUN 介面管理 | 需要建立和管理 TUN 介面 |
| `Socks5Proxy` | 本地 SOCKS5 伺服器 | hev-tunnel 的連線目標 |
| `DefaultConnectionRouter` | 路由決策 | 保留靈活的路由配置 |

---

## 📅 遷移總體規劃

### 時程表

| 階段 | 時間 | 主要任務 |
|------|------|----------|
| 基礎設施準備 | 7 天 | Native 整合、配置系統設計 |
| 核心遷移 | 10 天 | 移除舊組件、服務流程重構 |
| 優化完善 | 10 天 | 監控系統適配、錯誤處理完善 |
| 驗收部署 | 11 天 | 整合測試、相容性驗證 |

### 里程碑

| 階段 | 里程碑 | 驗收標準 |
|------|--------|----------|
| 基礎設施 | Native 整合完成 | hev-tunnel 可透過 JNI 啟動 |
| 核心遷移 | 舊組件移除完成 | VPN 可正常啟動和連線 |
| 優化完善 | 監控系統完成 | 統計和監控功能正常 |
| 驗收測試 | 相容性測試通過 | 支援 Android 5.0-14.0 |

---

## 🛠 詳細實作步驟

## 階段一：基礎設施準備（7 天）

### 第 1 天：準備 hev-socks5-tunnel 源碼

**1.1 下載和整合源碼**

```bash
# 在專案根目錄下
cd app/src/main/cpp/
git submodule add https://github.com/heiher/hev-socks5-tunnel.git
cd hev-socks5-tunnel
git checkout v2.6.3  # 使用穩定版本
```

**1.2 修改 CMakeLists.txt**

```cmake
# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("hev-tunnel-bridge")

# 設置標準
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

# 添加 hev-socks5-tunnel 依賴
add_subdirectory(hev-socks5-tunnel)

# 創建 JNI 橋接庫
add_library(
    hev-tunnel-bridge
    SHARED
    native-lib.cpp
    hev-tunnel-jni.cpp
)

# 連結庫
target_link_libraries(
    hev-tunnel-bridge
    hev-socks5-tunnel-static
    android
    log
)

target_compile_options(hev-tunnel-bridge PRIVATE -Wall -Wextra)
```

### 第 2-3 天：建立 JNI 橋接層

**2.1 創建 JNI 接口**

```cpp
// app/src/main/cpp/hev-tunnel-jni.cpp
#include <jni.h>
#include <unistd.h>
#include <android/log.h>
#include <pthread.h>

#define LOG_TAG "HevTunnelJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
    int hev_main(int argc, char *argv[]);
    void hev_stop(void);
}

static pthread_t tunnel_thread;
static bool tunnel_running = false;

typedef struct {
    int tun_fd;
    char config_path[256];
} tunnel_args_t;

static void* tunnel_thread_func(void* arg) {
    tunnel_args_t* args = (tunnel_args_t*)arg;
    
    // 設置環境變數傳遞 TUN fd
    char fd_str[16];
    snprintf(fd_str, sizeof(fd_str), "%d", args->tun_fd);
    setenv("HEV_TUN_FD", fd_str, 1);
    
    // 準備 argv
    char* argv[] = {
        "hev-socks5-tunnel",
        args->config_path,
        nullptr
    };
    
    LOGD("Starting hev-tunnel with config: %s", args->config_path);
    int result = hev_main(2, argv);
    LOGD("hev-tunnel exited with code: %d", result);
    
    tunnel_running = false;
    free(args);
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_vpntest_tunnel_HevTunnelManager_startTunnelNative(
    JNIEnv *env, jobject thiz, jint tun_fd, jstring config_path) {
    
    if (tunnel_running) {
        LOGE("Tunnel already running");
        return -1;
    }
    
    const char *config_str = env->GetStringUTFChars(config_path, nullptr);
    
    tunnel_args_t* args = (tunnel_args_t*)malloc(sizeof(tunnel_args_t));
    args->tun_fd = tun_fd;
    strncpy(args->config_path, config_str, sizeof(args->config_path) - 1);
    args->config_path[sizeof(args->config_path) - 1] = '\0';
    
    env->ReleaseStringUTFChars(config_path, config_str);
    
    int result = pthread_create(&tunnel_thread, nullptr, tunnel_thread_func, args);
    if (result == 0) {
        tunnel_running = true;
        LOGD("Tunnel thread created successfully");
        return 0;
    } else {
        LOGE("Failed to create tunnel thread: %d", result);
        free(args);
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_vpntest_tunnel_HevTunnelManager_stopTunnelNative(
    JNIEnv *env, jobject thiz) {
    
    if (!tunnel_running) {
        LOGD("Tunnel not running");
        return;
    }
    
    LOGD("Stopping tunnel...");
    hev_stop();
    pthread_join(tunnel_thread, nullptr);
    tunnel_running = false;
    LOGD("Tunnel stopped");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpntest_tunnel_HevTunnelManager_isRunningNative(
    JNIEnv *env, jobject thiz) {
    return tunnel_running;
}
```

**2.2 建立 Java 橋接層**

```kotlin
// app/src/main/java/com/example/vpntest/tunnel/HevTunnelManager.kt
package com.example.vpntest.tunnel

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class HevTunnelManager {
    
    companion object {
        private const val TAG = "HevTunnelManager"
        
        init {
            try {
                System.loadLibrary("hev-tunnel-bridge")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }
    
    private external fun startTunnelNative(tunFd: Int, configPath: String): Int
    private external fun stopTunnelNative()
    private external fun isRunningNative(): Boolean
    
    private val isInitialized = AtomicBoolean(false)
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun startTunnel(tunFd: Int, configPath: String): Boolean {
        return try {
            Log.d(TAG, "Starting tunnel with fd=$tunFd, config=$configPath")
            
            val result = startTunnelNative(tunFd, configPath)
            if (result == 0) {
                isInitialized.set(true)
                Log.i(TAG, "Tunnel started successfully")
                true
            } else {
                Log.e(TAG, "Failed to start tunnel, result=$result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting tunnel", e)
            false
        }
    }
    
    fun stopTunnel(): Boolean {
        return try {
            if (!isInitialized.get()) {
                Log.w(TAG, "Tunnel not initialized")
                return true
            }
            
            Log.d(TAG, "Stopping tunnel...")
            stopTunnelNative()
            isInitialized.set(false)
            Log.i(TAG, "Tunnel stopped successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping tunnel", e)
            false
        }
    }
    
    fun isRunning(): Boolean {
        return try {
            isInitialized.get() && isRunningNative()
        } catch (e: Exception) {
            Log.e(TAG, "Exception checking tunnel status", e)
            false
        }
    }
    
    fun cleanup() {
        stopTunnel()
        managerScope.cancel()
    }
}
```

### 第 4-5 天：配置系統設計

**4.1 配置資料類別**

```kotlin
// app/src/main/java/com/example/vpntest/config/HevTunnelConfig.kt
package com.example.vpntest.config

data class HevTunnelConfig(
    val tunnel: TunnelConfig = TunnelConfig(),
    val socks5: Socks5Config = Socks5Config(),
    val tcp: TcpConfig = TcpConfig(),
    val misc: MiscConfig = MiscConfig()
) {
    
    data class TunnelConfig(
        val name: String = "android-vpn",
        val mtu: Int = 1500,
        val ipv4: Boolean = true,
        val ipv6: Boolean = false
    )
    
    data class Socks5Config(
        val address: String = "127.0.0.1",
        val port: Int = 1080,
        val username: String? = null,
        val password: String? = null
    )
    
    data class TcpConfig(
        val connect_timeout: Int = 5000,
        val read_timeout: Int = 60000,
        val write_timeout: Int = 60000
    )
    
    data class MiscConfig(
        val task_stack_size: Int = 20480,
        val log_file: String? = null,
        val log_level: String = "warn"
    )
    
    fun toYamlString(): String {
        return buildString {
            appendLine("tunnel:")
            appendLine("  name: ${tunnel.name}")
            appendLine("  mtu: ${tunnel.mtu}")
            appendLine("  ipv4: ${tunnel.ipv4}")
            appendLine("  ipv6: ${tunnel.ipv6}")
            appendLine()
            
            appendLine("socks5:")
            appendLine("  address: ${socks5.address}")
            appendLine("  port: ${socks5.port}")
            appendLine("  username: ${socks5.username ?: "~"}")
            appendLine("  password: ${socks5.password ?: "~"}")
            appendLine()
            
            appendLine("tcp:")
            appendLine("  connect-timeout: ${tcp.connect_timeout}")
            appendLine("  read-timeout: ${tcp.read_timeout}")
            appendLine("  write-timeout: ${tcp.write_timeout}")
            appendLine()
            
            appendLine("misc:")
            appendLine("  task-stack-size: ${misc.task_stack_size}")
            if (misc.log_file != null) {
                appendLine("  log-file: ${misc.log_file}")
            }
            appendLine("  log-level: ${misc.log_level}")
        }
    }
    
    companion object {
        fun createDefault(): HevTunnelConfig = HevTunnelConfig()
        
        fun createPerformanceOptimized(): HevTunnelConfig = HevTunnelConfig(
            tunnel = TunnelConfig(mtu = 1400),
            tcp = TcpConfig(
                connect_timeout = 3000,
                read_timeout = 30000,
                write_timeout = 30000
            ),
            misc = MiscConfig(
                task_stack_size = 16384,
                log_level = "error"
            )
        )
    }
}
```

**4.2 配置管理器**

```kotlin
// app/src/main/java/com/example/vpntest/config/ConfigManager.kt
package com.example.vpntest.config

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
    
    enum class ConfigType {
        DEFAULT,
        PERFORMANCE
    }
}
```

### 第 6-7 天：監控系統

**6.1 進程監控器**

```kotlin
// app/src/main/java/com/example/vpntest/tunnel/TunnelMonitor.kt
package com.example.vpntest.tunnel

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TunnelMonitor(private val hevTunnelManager: HevTunnelManager) {
    
    companion object {
        private const val TAG = "TunnelMonitor"
        private const val MONITOR_INTERVAL_MS = 5000L
    }
    
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    
    private val _status = MutableStateFlow(TunnelStatus.STOPPED)
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    
    private var restartCallback: (suspend () -> Boolean)? = null
    
    fun setRestartCallback(callback: suspend () -> Boolean) {
        restartCallback = callback
    }
    
    fun startMonitoring() {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "Monitor already running")
            return
        }
        
        Log.d(TAG, "Starting tunnel monitor")
        
        monitorJob = monitorScope.launch {
            _status.value = TunnelStatus.MONITORING
            
            while (isActive) {
                try {
                    val isRunning = hevTunnelManager.isRunning()
                    
                    when (_status.value) {
                        TunnelStatus.MONITORING -> {
                            if (isRunning) {
                                _status.value = TunnelStatus.RUNNING
                            }
                        }
                        TunnelStatus.RUNNING -> {
                            if (!isRunning) {
                                Log.w(TAG, "Tunnel process died unexpectedly")
                                _status.value = TunnelStatus.CRASHED
                                handleTunnelCrash()
                            }
                        }
                        else -> {
                            // 其他狀態
                        }
                    }
                    
                    delay(MONITOR_INTERVAL_MS)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error", e)
                    delay(1000)
                }
            }
        }
    }
    
    fun stopMonitoring() {
        Log.d(TAG, "Stopping tunnel monitor")
        monitorJob?.cancel()
        monitorJob = null
        _status.value = TunnelStatus.STOPPED
    }
    
    private suspend fun handleTunnelCrash() {
        Log.i(TAG, "Attempting to restart tunnel")
        
        try {
            _status.value = TunnelStatus.RESTARTING
            val success = restartCallback?.invoke() ?: false
            
            if (success) {
                Log.i(TAG, "Tunnel restart successful")
                _status.value = TunnelStatus.RUNNING
            } else {
                Log.e(TAG, "Tunnel restart failed")
                _status.value = TunnelStatus.FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart tunnel", e)
            _status.value = TunnelStatus.FAILED
        }
    }
    
    fun cleanup() {
        stopMonitoring()
        monitorScope.cancel()
    }
}

enum class TunnelStatus {
    STOPPED,
    MONITORING,
    RUNNING,
    CRASHED,
    RESTARTING,
    FAILED
}
```

---

## 階段二：核心遷移（10 天）

### 第 8-10 天：移除舊組件

**8.1 建立相容性標記**

```kotlin
// app/src/main/java/com/example/vpntest/migration/MigrationFlags.kt
package com.example.vpntest.migration

object MigrationFlags {
    const val USE_HEV_TUNNEL = true
    const val KEEP_LEGACY_COMPONENTS = false
    
    fun shouldUseLegacyProcessor(): Boolean {
        return !USE_HEV_TUNNEL || BuildConfig.DEBUG && KEEP_LEGACY_COMPONENTS
    }
}
```

### 第 11-17 天：服務流程重構

**11.1 重構 ZyxelVpnService**

```kotlin
// 修改 app/src/main/java/com/example/vpntest/ZyxelVpnService.kt
class ZyxelVpnService : VpnService() {
    
    // 新增 hev-tunnel 組件
    private lateinit var hevTunnelManager: HevTunnelManager
    private lateinit var configManager: ConfigManager
    private lateinit var tunnelMonitor: TunnelMonitor
    
    // 保留核心組件
    private lateinit var networkManager: VpnNetworkManager
    private lateinit var socks5Proxy: Socks5Proxy
    private lateinit var connectionRouter: DefaultConnectionRouter
    
    private fun initializeComponents() {
        networkManager = VpnNetworkManager(this, this)
        connectionRouter = DefaultConnectionRouter()
        socks5Proxy = Socks5Proxy(this, 1080)
        
        if (MigrationFlags.USE_HEV_TUNNEL) {
            hevTunnelManager = HevTunnelManager()
            configManager = ConfigManager(this)
            tunnelMonitor = TunnelMonitor(hevTunnelManager)
        }
    }
    
    fun startVpn() {
        if (isRunning) return
        
        serviceScope.launch {
            try {
                if (MigrationFlags.USE_HEV_TUNNEL) {
                    startVpnWithHevTunnel()
                } else {
                    // 保留舊邏輯用於回滾
                    startVpnWithLegacyProcessor()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                stopVpn()
            }
        }
    }
    
    private suspend fun startVpnWithHevTunnel() {
        Log.i(TAG, "Starting VPN with hev-socks5-tunnel...")
        
        // 1. 建立 VPN 介面
        if (!networkManager.setupVpnInterface()) {
            Log.e(TAG, "Failed to setup VPN interface")
            return
        }
        
        // 2. 啟動 SOCKS5 代理
        socks5Proxy.start()
        connectionRouter.setDefaultProxy(socks5Proxy)
        
        // 3. 生成配置並啟動 tunnel
        val configPath = configManager.generateConfig(1080)
        val tunFd = getTunFileDescriptor()
        
        if (!hevTunnelManager.startTunnel(tunFd, configPath)) {
            Log.e(TAG, "Failed to start hev-tunnel")
            return
        }
        
        // 4. 開始監控
        tunnelMonitor.setRestartCallback { restartTunnel() }
        tunnelMonitor.startMonitoring()
        
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification("VPN Connected"))
        Log.i(TAG, "VPN started successfully with hev-tunnel")
    }
    
    private suspend fun restartTunnel(): Boolean {
        return try {
            hevTunnelManager.stopTunnel()
            delay(1000)
            
            val tunFd = getTunFileDescriptor()
            val configPath = configManager.getConfigPath()
            hevTunnelManager.startTunnel(tunFd, configPath)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during tunnel restart", e)
            false
        }
    }
    
    private fun getTunFileDescriptor(): Int {
        return networkManager.getVpnInterface()?.fd ?: -1
    }
    
    fun stopVpn() {
        if (!isRunning) return
        
        Log.i(TAG, "Stopping VPN service...")
        isRunning = false
        
        try {
            if (MigrationFlags.USE_HEV_TUNNEL) {
                tunnelMonitor.stopMonitoring()
                hevTunnelManager.stopTunnel()
            }
            
            socks5Proxy.stop()
            networkManager.teardownVpnInterface()
            stopForeground(STOP_FOREGROUND_REMOVE)
            
            Log.i(TAG, "VPN service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
        }
    }
    
    fun getSystemStatus(): String {
        return buildString {
            appendLine("=== VPN System Status ===")
            appendLine("VPN Running: $isRunning")
            appendLine("Using hev-tunnel: ${MigrationFlags.USE_HEV_TUNNEL}")
            
            if (MigrationFlags.USE_HEV_TUNNEL) {
                appendLine("Tunnel Running: ${hevTunnelManager.isRunning()}")
                appendLine("Tunnel Status: ${tunnelMonitor.status.value}")
            }
            
            appendLine()
            appendLine(networkManager.getNetworkInfo())
            appendLine()
            appendLine(connectionRouter.getRoutingInfo())
            
            val proxyStats = socks5Proxy.getStats()
            appendLine()
            appendLine("=== SOCKS5 Proxy Stats ===")
            appendLine("Healthy: ${socks5Proxy.isHealthy()}")
            appendLine("Connections: ${proxyStats.connectionCount}")
            appendLine("Bytes transferred: ${proxyStats.bytesTransferred}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        
        if (MigrationFlags.USE_HEV_TUNNEL) {
            tunnelMonitor.cleanup()
            hevTunnelManager.cleanup()
        }
        
        serviceScope.cancel()
    }
}
```

---

## 🛡 風險管理與部署

### 主要風險

| 風險 | 機率 | 影響 | 緩解策略 |
|------|------|------|----------|
| Native 整合失敗 | 中 | 高 | 建立回滾機制，保留原始程式碼 |
| 相容性問題 | 高 | 中 | 分階段測試，支援新舊切換 |
| 效能下降 | 低 | 中 | 效能基準測試，優化配置 |

### 回滾策略

1. **功能開關**：使用 `MigrationFlags` 快速切換新舊實作
2. **版本管理**：保留完整的舊程式碼分支
3. **監控告警**：即時監控系統狀態，發現問題立即回滾

### 部署策略

**階段性部署：**
1. **內部測試**：開發團隊內部驗證基本功能
2. **Alpha 測試**：小範圍使用者測試穩定性
3. **Beta 測試**：擴大測試範圍，收集反饋
4. **正式發布**：全量發布新版本

### 監控指標

**關鍵指標：**
- VPN 連線成功率
- 平均連線建立時間
- 記憶體使用量
- CPU 使用率
- 崩潰率

### 成功標準

- [ ] VPN 可正常啟動和停止
- [ ] 網路連線功能正常
- [ ] 支援 Android 5.0 以上版本
- [ ] 記憶體使用量不超過原版本 20%
- [ ] 崩潰率低於 1%

---

## 📚 參考資料

1. [hev-socks5-tunnel GitHub](https://github.com/heiher/hev-socks5-tunnel)
2. [Android VPN Service 官方文件](https://developer.android.com/reference/android/net/VpnService)
3. [Android NDK 開發指南](https://developer.android.com/ndk)

---

**文件維護**：請在實作過程中持續更新此文件，記錄實際遇到的問題和解決方案。