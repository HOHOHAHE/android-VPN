# Android VPN with HEV Socks5 Tunnel

基於 HEV Socks5 Tunnel 的高效能 Android VPN 應用程式。

## 🌟 項目概述

本項目實現了一個功能完整的 Android VPN 應用，整合了高效能的 HEV Socks5 Tunnel 作為核心代理引擎。支援新舊架構混合模式，確保平穩遷移和向後相容性。

## 🏗️ 架構說明

### 新架構（HEV Tunnel）
- **HevTunnelManager**: 核心 tunnel 管理器
- **TunnelMonitor**: 智能監控和自動恢復
- **ConfigManager**: 配置管理和驗證
- **MigrationFlags**: 遷移控制和功能開關

### 舊架構（向後相容）
- **VpnPacketProcessor**: 傳統封包處理器（已標記為廢棄）
- **協議處理器**: TCP/UDP/ICMP 處理組件
- **會話管理**: 連線追蹤和狀態管理

## 📋 主要功能

### ✅ 核心功能
- [x] HEV Socks5 Tunnel 整合
- [x] 智能監控和故障恢復
- [x] 配置管理和驗證
- [x] 遷移控制機制
- [x] 效能監控和統計
- [x] 完整的錯誤處理
- [x] 記憶體洩漏防護

### ✅ 進階功能
- [x] 新舊架構並存模式
- [x] 自動故障轉移
- [x] 詳細日誌和除錯支援
- [x] 集成測試套件
- [x] 效能基準測試
- [x] 壓力測試和穩定性驗證

## 🚀 快速開始

### 環境需求
- Android Studio Arctic Fox 或更高版本
- Android SDK API 21+ (Android 5.0+)
- NDK r21 或更高版本
- CMake 3.10.2+

### 編譯步驟

1. **克隆專案**
```bash
git clone <repository-url>
cd android-VPN
```

2. **配置 NDK**
```bash
# 在 local.properties 中設定 NDK 路徑
echo "ndk.dir=/path/to/your/ndk" >> local.properties
```

3. **編譯專案**
```bash
./gradlew assembleDebug
```

4. **執行測試**
```bash
./gradlew test
./gradlew connectedAndroidTest
```

## ⚙️ 配置說明

### 遷移標誌配置

在 [`MigrationFlags.kt`](app/src/main/java/com/example/vpntest/migration/MigrationFlags.kt) 中配置：

```kotlin
// 主要遷移標誌
const val USE_HEV_TUNNEL = true              // 啟用 HEV Tunnel
const val KEEP_LEGACY_COMPONENTS = false     // 保留舊組件

// 功能標誌
const val ENABLE_PERFORMANCE_MONITORING = true
const val ENABLE_DETAILED_LOGGING = true
const val ENABLE_INTEGRATION_TESTS = true
```

### HEV Tunnel 配置

在 [`hev-tunnel-config-template.yaml`](app/src/main/assets/hev-tunnel-config-template.yaml) 中配置：

```yaml
tunnel:
  name: hev-socks5-tunnel
  
socks5:
  port: 1080
  address: 127.0.0.1
  username: your_username
  password: your_password

misc:
  task-stack-size: 8192
  connect-timeout: 5000
  read-write-timeout: 60000
```

## 🔧 開發指南

### 架構模式

本項目採用分層架構：

```
┌─────────────────────────────────┐
│          UI Layer               │
│    (MainActivity, Service)      │
├─────────────────────────────────┤
│        Business Layer          │
│  (HevTunnelManager, Monitor)    │
├─────────────────────────────────┤
│         Data Layer              │
│   (ConfigManager, Flags)        │
├─────────────────────────────────┤
│        Native Layer             │
│    (HEV Socks5 Tunnel C++)      │
└─────────────────────────────────┘
```

### 核心組件說明

#### HevTunnelManager
負責 tunnel 生命週期管理：
```kotlin
val manager = HevTunnelManager()
val success = manager.startTunnel(tunFd, configPath)
```

#### TunnelMonitor
提供智能監控功能：
```kotlin
val monitor = TunnelMonitor(manager)
monitor.setRestartCallback { 
    // 自定義重啟邏輯
    manager.restartTunnel(tunFd, configPath)
}
monitor.startMonitoring()
```

#### ConfigManager
管理配置文件：
```kotlin
val configManager = ConfigManager(context)
val config = HevConfig(
    serverAddress = "proxy.example.com",
    serverPort = 1080,
    username = "user",
    password = "pass"
)
val configPath = configManager.createConfigFile(config)
```

### 新增功能開發

1. **建立新的功能分支**
```bash
git checkout -b feature/new-feature
```

2. **遵循命名慣例**
- Kotlin 文件: `PascalCase.kt`
- 資源文件: `snake_case.xml`
- 常數: `UPPER_SNAKE_CASE`

3. **加入適當的測試**
```kotlin
@Test
fun testNewFeature() {
    // 測試新功能
}
```

4. **更新文檔**
- 更新 README.md
- 加入 KDoc 註解
- 更新遷移指南

## 🧪 測試指南

### 執行集成測試

```kotlin
val integrationTest = HevIntegrationTest(context)
val summary = integrationTest.runFullIntegrationTest()
println(summary.generateReport())
```

### 測試涵蓋範圍
- ✅ 基礎功能測試
- ✅ 遷移開關測試  
- ✅ 錯誤處理測試
- ✅ 記憶體洩漏測試
- ✅ 效能基準測試
- ✅ 壓力測試

### 測試命令

```bash
# 單元測試
./gradlew test

# 集成測試
./gradlew connectedAndroidTest

# 程式碼覆蓋率
./gradlew jacocoTestReport
```

## 📊 效能監控

### 監控指標
- **記憶體使用量**: 透過 `HevTunnelManager.getStats()`
- **錯誤率**: 監控 tunnel 重啟次數
- **回應時間**: 測量 tunnel 啟動時間
- **穩定性**: 追蹤崩潰和恢復事件

### 效能最佳化
1. **JNI 最佳化**: 減少 Java-Native 調用開銷
2. **記憶體管理**: 及時釋放資源，避免洩漏
3. **非同步操作**: 避免阻塞主執行緒
4. **連接池**: 重用網路連接

## 🐛 故障排除

### 常見問題

#### 1. Native Library 載入失敗
```
Error: java.lang.UnsatisfiedLinkError: dlopen failed
```
**解決方案**:
- 確認 NDK 版本相容性
- 檢查 ABI 設定
- 重新編譯 native 組件

#### 2. Tunnel 啟動失敗
```
Error: Failed to start tunnel, result=-1
```
**解決方案**:
- 檢查配置文件格式
- 驗證網路權限
- 確認 TUN interface 可用

#### 3. 記憶體洩漏
```
Warning: Memory increase detected
```
**解決方案**:
- 確保調用 `cleanup()` 方法
- 檢查 Coroutine 取消邏輯
- 驗證 Native 資源釋放

### 除錯指令

```bash
# 啟用詳細日誌
adb shell setprop log.tag.HevTunnelManager VERBOSE
adb shell setprop log.tag.TunnelMonitor VERBOSE

# 檢查記憶體使用
adb shell dumpsys meminfo com.example.vpntest

# 監控網路流量
adb shell tcpdump -i any -w /sdcard/capture.pcap
```

## 📚 API 文檔

### 核心 API

#### HevTunnelManager
```kotlin
class HevTunnelManager {
    fun startTunnel(tunFd: Int, configPath: String): Boolean
    fun stopTunnel(): Boolean
    fun isRunning(): Boolean
    fun restartTunnel(tunFd: Int, configPath: String): Boolean
    fun getStats(): String
    fun cleanup()
}
```

#### TunnelMonitor
```kotlin
class TunnelMonitor(manager: HevTunnelManager) {
    fun startMonitoring()
    fun stopMonitoring()
    fun setRestartCallback(callback: suspend () -> Boolean)
    val status: StateFlow<TunnelStatus>
    fun forceRestart(): Boolean
    fun cleanup()
}
```

#### MigrationFlags
```kotlin
object MigrationFlags {
    fun shouldUseLegacyProcessor(): Boolean
    fun shouldUseHevTunnel(): Boolean
    fun getMigrationInfo(): String
    fun validateMigrationFlags(): ValidationResult
}
```

## 🗂️ 專案結構

```
android-VPN/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/vpntest/
│   │   │   ├── core/                    # 核心組件（舊架構）
│   │   │   ├── hev/                     # HEV Tunnel 組件
│   │   │   ├── migration/               # 遷移控制
│   │   │   ├── network/                 # 網路管理
│   │   │   └── protocols/               # 協議處理
│   │   ├── cpp/                         # Native 代碼
│   │   │   ├── hev-socks5-tunnel/       # HEV 源碼
│   │   │   └── hev-tunnel-jni.cpp       # JNI 橋接
│   │   └── assets/                      # 配置模板
│   └── build.gradle.kts                 # 模組配置
├── docs/                                # 文檔
│   ├── MIGRATION_PLAN.md               # 遷移計畫
│   ├── HEV_TUN2SOCKS_MIGRATION.md      # HEV 整合文檔
│   └── PHASE*_SUMMARY.md               # 階段總結
└── README.md                           # 專案說明
```

## 🔄 版本歷史

### v2.0.0 (計畫中)
- [x] 完整 HEV Tunnel 整合
- [x] 智能監控和故障恢復
- [x] 完善的錯誤處理
- [x] 集成測試套件
- [ ] 效能最佳化
- [ ] 生產環境部署

### v1.2.0 (當前)
- [x] HEV Tunnel 基礎整合
- [x] 遷移控制機制
- [x] 混合模式支援
- [x] 基礎監控功能

### v1.1.0
- [x] 基礎 VPN 功能
- [x] 協議處理器
- [x] 會話管理

## 🤝 貢獻指南

1. **Fork 專案**
2. **建立功能分支** (`git checkout -b feature/AmazingFeature`)
3. **提交變更** (`git commit -m 'Add some AmazingFeature'`)
4. **推送分支** (`git push origin feature/AmazingFeature`)
5. **開啟 Pull Request**

### 程式碼規範
- 遵循 Kotlin 官方程式碼風格
- 加入適當的 KDoc 註解
- 確保測試通過
- 更新相關文檔

## 📄 授權條款

本專案採用 MIT 授權條款 - 詳見 [LICENSE](LICENSE) 文件

## 🙋‍♂️ 支援與聯絡

- 📧 Email: support@example.com
- 🐛 Bug Reports: [GitHub Issues](https://github.com/example/android-vpn/issues)
- 💬 Discussions: [GitHub Discussions](https://github.com/example/android-vpn/discussions)

## 🔗 相關連結

- [HEV Socks5 Tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [Android VPN API](https://developer.android.com/reference/android/net/VpnService)
- [CMake for Android](https://developer.android.com/ndk/guides/cmake)

---

> 💡 **提示**: 定期查看 [`docs/`](docs/) 目錄獲取最新的技術文檔和遷移指南。
