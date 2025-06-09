# HEV Socks5 Tunnel 部署指南

## 📋 部署概述

本指南詳細說明如何部署和配置基於 HEV Socks5 Tunnel 的 Android VPN 應用程式。

## 🔧 部署前準備

### 系統需求

#### 開發環境
- **Android Studio**: Arctic Fox 2020.3.1 或更高版本
- **Android SDK**: API Level 21+ (Android 5.0+)
- **NDK**: r21 或更高版本
- **CMake**: 3.10.2+
- **Gradle**: 7.0+

#### 目標設備
- **Android 版本**: 5.0 (API 21) 或更高
- **RAM**: 最少 2GB，建議 4GB+
- **存儲空間**: 最少 100MB 可用空間
- **權限**: VPN 服務權限

#### 網路需求
- **代理伺服器**: 支援 Socks5 協議的代理伺服器
- **網路連線**: 穩定的網際網路連線
- **防火牆**: 確保代理端口可訪問

### 依賴項檢查

```bash
# 檢查 Android SDK
$ANDROID_HOME/tools/bin/sdkmanager --list

# 檢查 NDK
$ANDROID_HOME/ndk/21.4.7075529/ndk-build -v

# 檢查 CMake
cmake --version
```

## 🛠️ 編譯配置

### 1. 環境變數設定

在 `local.properties` 中配置：

```properties
# Android SDK 路徑
sdk.dir=/path/to/Android/Sdk

# NDK 路徑
ndk.dir=/path/to/Android/Sdk/ndk/21.4.7075529

# CMake 路徑 (可選)
cmake.dir=/path/to/cmake

# 簽名配置 (生產環境)
RELEASE_STORE_FILE=/path/to/keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

### 2. Gradle 配置

在 `app/build.gradle.kts` 中確認：

```kotlin
android {
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.example.vpntest"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.10.2"
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### 3. 簽名配置

生產環境需要配置應用簽名：

```bash
# 生成 Keystore
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias vpn-app

# 在 app/build.gradle.kts 中配置
signingConfigs {
    create("release") {
        storeFile = file(project.findProperty("RELEASE_STORE_FILE") ?: "release-key.jks")
        storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
        keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
        keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
    }
}
```

## 🚀 編譯步驟

### 除錯版本編譯

```bash
# 清理專案
./gradlew clean

# 編譯除錯版本
./gradlew assembleDebug

# 安裝到設備
./gradlew installDebug
```

### 發佈版本編譯

```bash
# 編譯發佈版本
./gradlew assembleRelease

# 產生簽名的 APK
./gradlew bundleRelease
```

### 編譯驗證

```bash
# 執行單元測試
./gradlew test

# 執行集成測試
./gradlew connectedAndroidTest

# 生成測試報告
./gradlew jacocoTestReport
```

## ⚙️ 應用配置

### 1. 遷移標誌配置

在 [`MigrationFlags.kt`](../app/src/main/java/com/example/vpntest/migration/MigrationFlags.kt) 中設定：

```kotlin
object MigrationFlags {
    // 生產環境配置
    const val USE_HEV_TUNNEL = true
    const val KEEP_LEGACY_COMPONENTS = false
    const val ENABLE_DETAILED_LOGGING = false  // 生產環境關閉
    const val LOG_SENSITIVE_DATA = false       // 生產環境必須關閉
    
    // 效能最佳化
    const val OPTIMIZE_MEMORY_USAGE = true
    const val ENABLE_JNI_OPTIMIZATION = true
    const val USE_ASYNC_OPERATIONS = true
}
```

### 2. HEV Tunnel 配置

創建配置文件範本：

```yaml
# hev-tunnel-config.yaml
tunnel:
  name: hev-socks5-tunnel
  mtu: 1500

socks5:
  port: 1080
  address: your-proxy-server.com
  username: your_username
  password: your_password

dns:
  port: 53
  address: 8.8.8.8

misc:
  task-stack-size: 8192
  connect-timeout: 5000
  read-write-timeout: 60000
  log-file: /data/data/com.example.vpntest/files/hev-tunnel.log
  log-level: warn  # 生產環境使用 warn 或 error
  pid-file: /data/data/com.example.vpntest/files/hev-tunnel.pid
```

### 3. Android 權限配置

在 `AndroidManifest.xml` 中確認：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<!-- VPN 服務權限 -->
<service
    android:name=".ZyxelVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

## 🔍 部署驗證

### 自動化測試

建立部署驗證腳本：

```bash
#!/bin/bash
# deploy_validation.sh

echo "🚀 Starting deployment validation..."

# 1. 編譯測試
echo "📦 Building application..."
./gradlew assembleRelease
if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

# 2. 單元測試
echo "🧪 Running unit tests..."
./gradlew test
if [ $? -ne 0 ]; then
    echo "❌ Unit tests failed"
    exit 1
fi

# 3. 安裝測試
echo "📱 Installing on device..."
adb install -r app/build/outputs/apk/release/app-release.apk
if [ $? -ne 0 ]; then
    echo "❌ Installation failed"
    exit 1
fi

# 4. 啟動測試
echo "🔄 Testing app launch..."
adb shell am start -n com.example.vpntest/.MainActivity
sleep 5

# 5. 檢查日誌
echo "📝 Checking logs..."
adb logcat -d | grep "HevTunnelManager\|TunnelMonitor" | tail -20

echo "✅ Deployment validation completed successfully!"
```

### 手動驗證步驟

1. **應用啟動驗證**
   - 應用正常啟動，無崩潰
   - UI 介面正確顯示
   - 權限請求正常

2. **VPN 功能驗證**
   - VPN 服務正常啟動
   - HEV Tunnel 連接成功
   - 網路流量正確路由

3. **監控功能驗證**
   - TunnelMonitor 正常運行
   - 自動重啟功能正常
   - 錯誤處理機制有效

4. **效能驗證**
   - 記憶體使用合理
   - CPU 使用率正常
   - 網路延遲可接受

## 📊 監控和日誌

### 日誌配置

在生產環境中配置適當的日誌等級：

```kotlin
// 在 Application 類中
class VpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 載入遷移配置
        MigrationFlags.logCurrentConfiguration()
        
        // 設定日誌過濾器
        if (!BuildConfig.DEBUG) {
            // 生產環境僅記錄重要訊息
            Log.isLoggable("HevTunnelManager", Log.INFO)
            Log.isLoggable("TunnelMonitor", Log.WARN)
        }
    }
}
```

### 效能監控

整合效能監控工具：

```kotlin
class PerformanceMonitor {
    fun startMonitoring() {
        if (MigrationFlags.isPerformanceMonitoringEnabled()) {
            // 記憶體監控
            monitorMemoryUsage()
            
            // CPU 監控
            monitorCpuUsage()
            
            // 網路監控
            monitorNetworkPerformance()
        }
    }
    
    private fun monitorMemoryUsage() {
        // 實作記憶體監控邏輯
    }
}
```

### 錯誤回報

配置錯誤回報機制：

```kotlin
class ErrorReporter {
    fun reportError(error: Throwable, context: String) {
        if (MigrationFlags.isStrictValidationEnabled()) {
            // 發送錯誤報告到監控系統
            sendErrorReport(error, context)
        }
        
        // 本地錯誤記錄
        Log.e("ErrorReporter", "Error in $context", error)
    }
}
```

## 🛡️ 安全考量

### 配置安全

1. **敏感資料保護**
   ```kotlin
   // 避免在日誌中記錄敏感資料
   if (!MigrationFlags.shouldLogSensitiveData()) {
       Log.d(TAG, "Connection established to ${address.replace(Regex("\\d+"), "*")}")
   }
   ```

2. **證書固定**
   ```kotlin
   // 實作證書固定以防中間人攻擊
   val certificatePinner = CertificatePinner.Builder()
       .add("your-proxy-server.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
       .build()
   ```

3. **權限最小化**
   - 僅請求必要的權限
   - 運行時權限檢查
   - 敏感操作用戶確認

### 網路安全

1. **流量加密**
   - 確保使用 TLS 加密
   - 驗證服務器證書
   - 使用強加密算法

2. **DNS 保護**
   - 防DNS洩漏
   - 使用安全DNS服務器
   - DNS over HTTPS 支援

## 🚨 故障排除

### 常見部署問題

#### 1. NDK 編譯失敗
```
Error: NDK is missing a "platforms" directory.
```
**解決方案**:
```bash
# 重新安裝 NDK
$ANDROID_HOME/tools/bin/sdkmanager "ndk;21.4.7075529"
```

#### 2. CMake 版本不相容
```
Error: CMake version 3.6.0 is too old.
```
**解決方案**:
```bash
# 升級 CMake
$ANDROID_HOME/tools/bin/sdkmanager "cmake;3.10.2.4988404"
```

#### 3. 簽名配置錯誤
```
Error: Keystore was tampered with, or password was incorrect
```
**解決方案**:
- 檢查 keystore 路徑
- 驗證密碼正確性
- 重新生成 keystore

#### 4. VPN 權限問題
```
SecurityException: Only system apps can use VpnService
```
**解決方案**:
- 確認 VpnService 正確配置
- 檢查 AndroidManifest.xml
- 用戶授權 VPN 權限

### 除錯指令

```bash
# 檢查應用狀態
adb shell dumpsys activity com.example.vpntest

# 監控系統日誌
adb logcat -s HevTunnelManager:V TunnelMonitor:V

# 檢查網路狀態
adb shell dumpsys connectivity

# 檢查 VPN 狀態
adb shell dumpsys vpn

# 效能分析
adb shell top -p $(adb shell pidof com.example.vpntest)
```

## 📈 效能最佳化

### 編譯最佳化

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            ndk {
                debugSymbolLevel = "NONE"  // 減少 APK 大小
            }
        }
    }
    
    packagingOptions {
        // 排除不必要的檔案
        exclude("META-INF/**")
        exclude("kotlin/**")
        exclude("**/*.kotlin_metadata")
    }
}
```

### 運行時最佳化

```kotlin
// 記憶體最佳化
class MemoryOptimizer {
    fun optimizeMemory() {
        if (MigrationFlags.isMemoryOptimizationEnabled()) {
            // 清理暫存檔案
            clearTempFiles()
            
            // 最佳化圖片快取
            optimizeImageCache()
            
            // 回收未使用的對象
            System.gc()
        }
    }
}
```

## 📋 部署清單

在生產部署前確認以下項目：

### 📝 編譯配置
- [ ] 正確的簽名配置
- [ ] 生產環境遷移標誌
- [ ] 關閉除錯日誌
- [ ] 啟用代碼混淆
- [ ] 移除測試代碼

### 🔧 功能驗證
- [ ] VPN 連接正常
- [ ] HEV Tunnel 運作
- [ ] 監控功能正常
- [ ] 錯誤處理有效
- [ ] 自動重啟功能

### 🛡️ 安全檢查
- [ ] 敏感資料保護
- [ ] 權限最小化
- [ ] 證書驗證
- [ ] 加密通信
- [ ] 無SQL注入風險

### 📊 效能測試
- [ ] 記憶體使用合理
- [ ] CPU 使用率正常
- [ ] 啟動時間可接受
- [ ] 網路延遲正常
- [ ] 電池消耗合理

### 📱 相容性測試
- [ ] 不同 Android 版本
- [ ] 不同設備型號
- [ ] 不同螢幕尺寸
- [ ] 不同網路環境
- [ ] 低記憶體設備

---

> 💡 **提示**: 部署前務必在測試環境中完整驗證所有功能，確保生產環境的穩定性和安全性。