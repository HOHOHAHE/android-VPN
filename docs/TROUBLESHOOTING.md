# HEV Socks5 Tunnel 故障排除指南

## 🚨 故障排除概述

本指南涵蓋了 HEV Socks5 Tunnel Android VPN 應用的常見問題和解決方案。

## 🔍 診斷工具

### 日誌收集

```bash
# 收集應用日誌
adb logcat -d | grep -E "(HevTunnelManager|TunnelMonitor|MigrationFlags)" > hev_tunnel.log

# 收集系統 VPN 日誌
adb logcat -d -s VpnService > vpn_system.log

# 收集記憶體使用資訊
adb shell dumpsys meminfo com.example.vpntest > memory_usage.log

# 收集網路連接資訊
adb shell dumpsys connectivity > network_status.log
```

### 診斷腳本

建立自動診斷腳本：

```bash
#!/bin/bash
# diagnose.sh

echo "🔍 HEV Tunnel 診斷工具"
echo "========================"

# 檢查應用狀態
echo "📱 檢查應用狀態..."
PACKAGE="com.example.vpntest"
PID=$(adb shell pidof $PACKAGE)

if [ -z "$PID" ]; then
    echo "❌ 應用未運行"
else
    echo "✅ 應用正在運行 (PID: $PID)"
fi

# 檢查 VPN 狀態
echo "🔒 檢查 VPN 狀態..."
VPN_STATUS=$(adb shell dumpsys vpn | grep -A 5 "$PACKAGE")
echo "$VPN_STATUS"

# 檢查網路連接
echo "🌐 檢查網路連接..."
adb shell ping -c 3 8.8.8.8

# 檢查日誌錯誤
echo "📝 檢查最近錯誤..."
adb logcat -d | grep -E "(ERROR|FATAL)" | grep -E "(HevTunnelManager|TunnelMonitor)" | tail -10

echo "✅ 診斷完成"
```

## 🐛 常見問題分類

### 1. 啟動和初始化問題

#### 問題：Native Library 載入失敗
```
E/HevTunnelManager: ❌ Failed to load HEV tunnel native library
java.lang.UnsatisfiedLinkError: dlopen failed: library "libhev-tunnel-bridge.so" not found
```

**原因分析**：
- NDK 編譯配置錯誤
- ABI 不匹配
- 庫檔案缺失

**解決步驟**：
1. 檢查 NDK 版本相容性
   ```bash
   $ANDROID_HOME/ndk/21.4.7075529/ndk-build -v
   ```

2. 驗證 ABI 配置
   ```kotlin
   // app/build.gradle.kts
   android {
       defaultConfig {
           ndk {
               abiFilters += listOf("arm64-v8a", "armeabi-v7a")
           }
       }
   }
   ```

3. 重新編譯 native 組件
   ```bash
   ./gradlew clean
   ./gradlew assembleDebug
   ```

4. 檢查 SO 檔案是否存在
   ```bash
   unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so$"
   ```

#### 問題：HevTunnelManager 初始化失敗
```
E/HevTunnelManager: ❌ Failed to start HEV tunnel: 配置文件無效或格式錯誤
```

**解決步驟**：
1. 檢查配置文件格式
   ```bash
   adb shell cat /data/data/com.example.vpntest/files/hev-tunnel-config.yaml
   ```

2. 驗證 YAML 語法
   ```bash
   # 使用線上 YAML 驗證器或本地工具
   yamllint /path/to/config.yaml
   ```

3. 檢查配置參數
   ```kotlin
   val config = HevConfig(
       serverAddress = "valid.proxy.server.com",  // 確保可解析
       serverPort = 1080,                         // 確保端口可訪問
       username = "valid_user",                   // 確保用戶名正確
       password = "valid_password"                // 確保密碼正確
   )
   ```

### 2. 連接和網路問題

#### 問題：Tunnel 連接失敗
```
W/TunnelMonitor: 💥 Tunnel process died unexpectedly
E/HevTunnelManager: ❌ Failed to start HEV tunnel: 網路不可用
```

**診斷步驟**：
1. 檢查網路連接
   ```bash
   adb shell ping -c 3 your-proxy-server.com
   ```

2. 測試代理伺服器
   ```bash
   # 使用 telnet 測試端口連通性
   adb shell telnet your-proxy-server.com 1080
   ```

3. 檢查防火牆設定
   ```bash
   # 檢查 iptables 規則
   adb shell su -c "iptables -L"
   ```

4. 驗證 DNS 解析
   ```bash
   adb shell nslookup your-proxy-server.com
   ```

**解決方案**：
1. 更換代理伺服器
2. 檢查網路設定
3. 確認防火牆規則
4. 使用 IP 地址替代域名

#### 問題：VPN 權限被拒絕
```
E/ZyxelVpnService: SecurityException: Only system apps can use VpnService
```

**解決步驟**：
1. 檢查 AndroidManifest.xml
   ```xml
   <service
       android:name=".ZyxelVpnService"
       android:permission="android.permission.BIND_VPN_SERVICE">
       <intent-filter>
           <action android:name="android.net.VpnService" />
       </intent-filter>
   </service>
   ```

2. 請求 VPN 權限
   ```kotlin
   val intent = VpnService.prepare(context)
   if (intent != null) {
       startActivityForResult(intent, VPN_REQUEST_CODE)
   }
   ```

3. 檢查系統 VPN 設定
   ```bash
   adb shell dumpsys vpn
   ```

### 3. 效能和記憶體問題

#### 問題：記憶體洩漏
```
W/HevIntegrationTest: ⚠️ Memory increase detected: 50MB
```

**診斷工具**：
1. 記憶體分析
   ```bash
   # 使用 Android Studio Memory Profiler
   # 或命令行工具
   adb shell dumpsys meminfo com.example.vpntest
   ```

2. 檢查對象引用
   ```kotlin
   // 使用 LeakCanary 檢測洩漏
   dependencies {
       debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.7'
   }
   ```

**解決方案**：
1. 確保調用 cleanup()
   ```kotlin
   override fun onDestroy() {
       hevTunnelManager.cleanup()
       tunnelMonitor.cleanup()
       super.onDestroy()
   }
   ```

2. 取消 Coroutine
   ```kotlin
   private val job = SupervisorJob()
   private val scope = CoroutineScope(Dispatchers.IO + job)
   
   fun cleanup() {
       job.cancel()
   }
   ```

3. 釋放 Native 資源
   ```kotlin
   external fun releaseNativeResources()
   
   fun cleanup() {
       releaseNativeResources()
   }
   ```

#### 問題：CPU 使用率過高
```
W/TunnelMonitor: 🔍 Status check: running=true, current=RUNNING
D/TunnelMonitor: 🔍 Status check: running=true, current=RUNNING
```

**診斷方法**：
1. 檢查監控間隔
   ```kotlin
   companion object {
       private const val MONITOR_INTERVAL_MS = 5000L  // 增加間隔
   }
   ```

2. 分析 CPU 使用
   ```bash
   adb shell top -p $(adb shell pidof com.example.vpntest)
   ```

**最佳化方案**：
1. 調整監控頻率
2. 使用事件驅動而非輪詢
3. 最佳化日誌輸出

### 4. 遷移和相容性問題

#### 問題：遷移標誌衝突
```
E/MigrationFlags: ❌ Configuration validation failed:
  • 同時啟用 HEV Tunnel 和 Legacy Processor，可能是除錯模式
```

**解決步驟**：
1. 檢查遷移配置
   ```kotlin
   val validation = MigrationFlags.validateMigrationFlags()
   if (!validation.isValid) {
       validation.issues.forEach { Log.e(TAG, it) }
   }
   ```

2. 更新配置
   ```kotlin
   object MigrationFlags {
       const val USE_HEV_TUNNEL = true
       const val KEEP_LEGACY_COMPONENTS = false  // 生產環境設為 false
   }
   ```

#### 問題：向後相容性問題
```
W/VpnPacketProcessor: 使用舊版封包處理器 - 此組件已被標記為廢棄
```

**解決方案**：
1. 啟用 HEV Tunnel
2. 逐步移除舊組件依賴
3. 更新相關代碼

### 5. 配置和設定問題

#### 問題：YAML 配置錯誤
```
E/ConfigManager: ❌ Failed to parse YAML config
```

**常見配置錯誤**：
1. 縮排錯誤
   ```yaml
   # 錯誤
   socks5:
   port: 1080  # 缺少縮排
   
   # 正確
   socks5:
     port: 1080
   ```

2. 資料類型錯誤
   ```yaml
   # 錯誤
   socks5:
     port: "1080"  # 應該是數字，不是字串
   
   # 正確
   socks5:
     port: 1080
   ```

3. 特殊字符處理
   ```yaml
   # 如果密碼包含特殊字符，需要引號
   socks5:
     password: "p@ssw0rd!"
   ```

**驗證工具**：
```bash
# 使用 yq 工具驗證 YAML
yq eval . config.yaml

# 或使用 Python
python -c "import yaml; yaml.safe_load(open('config.yaml'))"
```

## 🔧 進階診斷技巧

### 網路抓包分析

```bash
# 在設備上抓包
adb shell su -c "tcpdump -i any -w /sdcard/capture.pcap"

# 分析特定流量
adb shell su -c "tcpdump -i any -n host your-proxy-server.com"
```

### JNI 除錯

1. 啟用 JNI 檢查
   ```kotlin
   // 在 Application 類中
   if (BuildConfig.DEBUG) {
       System.setProperty("java.library.path", "/system/lib")
   }
   ```

2. 檢查 JNI 調用
   ```bash
   adb shell setprop debug.checkjni 1
   ```

### 效能分析

1. 使用 Systrace
   ```bash
   python systrace.py -t 10 -o trace.html sched freq idle am wm gfx view binder_driver hal dalvik camera input res
   ```

2. Method Tracing
   ```kotlin
   Debug.startMethodTracing("hev_tunnel")
   // 執行相關操作
   Debug.stopMethodTracing()
   ```

## 📋 故障排除清單

### 🔍 初步診斷
- [ ] 檢查應用是否正在運行
- [ ] 確認 VPN 權限已授權
- [ ] 驗證網路連接正常
- [ ] 檢查配置文件格式
- [ ] 確認代理伺服器可訪問

### 🛠️ 深度診斷
- [ ] 分析日誌錯誤信息
- [ ] 檢查記憶體使用情況
- [ ] 監控 CPU 使用率
- [ ] 驗證 Native 庫載入
- [ ] 測試遷移標誌設定

### 🔄 系統修復
- [ ] 重新啟動應用
- [ ] 清除應用資料
- [ ] 重新安裝應用
- [ ] 重啟設備
- [ ] 更新代理設定

### 📊 效能最佳化
- [ ] 調整監控間隔
- [ ] 最佳化日誌輸出
- [ ] 釋放不需要的資源
- [ ] 調整記憶體分配
- [ ] 最佳化網路設定

## 🆘 緊急修復步驟

### 快速恢復流程

1. **立即停止服務**
   ```kotlin
   // 在緊急情況下強制停止
   hevTunnelManager.stopTunnel()
   tunnelMonitor.stopMonitoring()
   ```

2. **切換到備用模式**
   ```kotlin
   // 臨時啟用舊處理器
   MigrationFlags.KEEP_LEGACY_COMPONENTS = true
   ```

3. **重置配置**
   ```kotlin
   // 使用預設配置
   val defaultConfig = HevConfig.getDefault()
   configManager.createConfigFile(defaultConfig)
   ```

4. **清理暫存資料**
   ```bash
   adb shell pm clear com.example.vpntest
   ```

### 回滾程序

如果新版本出現嚴重問題：

1. **準備回滾檔案**
   - 保留上一個穩定版本的 APK
   - 備份配置文件
   - 記錄用戶設定

2. **執行回滾**
   ```bash
   # 解除安裝新版本
   adb uninstall com.example.vpntest
   
   # 安裝舊版本
   adb install app-stable.apk
   
   # 恢復配置
   adb push backup-config.yaml /sdcard/
   ```

## 📞 技術支援

### 問題回報格式

當需要技術支援時，請提供：

1. **基本資訊**
   - 設備型號和 Android 版本
   - 應用版本號
   - 遷移階段（Legacy/Hybrid/HEV）

2. **錯誤詳情**
   - 具體錯誤訊息
   - 重現步驟
   - 發生頻率

3. **環境資訊**
   - 網路環境
   - 代理伺服器資訊
   - 相關日誌檔案

4. **診斷資料**
   ```bash
   # 收集診斷包
   ./diagnose.sh > diagnostic_report.txt
   adb bugreport diagnostic_bugreport.zip
   ```

### 聯繫方式

- 📧 技術支援: tech-support@example.com
- 🐛 Bug 回報: https://github.com/example/android-vpn/issues
- 💬 社群討論: https://github.com/example/android-vpn/discussions

---

> 💡 **提示**: 大多數問題都可以通過重新啟動應用或重新安裝來解決。如果問題持續存在，請收集詳細的日誌資訊並聯繫技術支援。