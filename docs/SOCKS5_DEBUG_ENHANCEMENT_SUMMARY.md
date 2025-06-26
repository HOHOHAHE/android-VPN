# SOCKS5 連線問題診斷改進總結

## 問題分析

根據完整的錯誤日誌分析，SOCKS5 連線失敗的關鍵問題是：

### 主要症狀：
1. **客戶端錯誤**：`LibreSSL/3.3.6: error:06FFF064:digital envelope routines:CRYPTO_internal:bad decrypt`
2. **服務端錯誤**：`java.io.IOException: Broken pipe` 在寫入操作中
3. **時序問題**：SSL 握手過程中連線意外中斷

### 根本原因分析：
從日誌時序可以看出：
```
17:22:29.260 - First data packet received from /142.250.204.36:443
17:22:29.260 - Relay A->P: read 2048 bytes
17:22:29.263 - Relay A->P: wrote 2048 bytes  
17:22:29.264 - Failed to write 324 bytes to /142.250.204.36:443 (Broken pipe)
```

這表明 SSL 握手期間發生了連線中斷，很可能是：
1. **SSL 握手時序競態條件**：代理在處理 SSL 握手資料流時出現競態
2. **連線狀態檢查不當**：Netty 通道狀態檢查與實際寫入操作之間的時間差

## 診斷改進措施

### 1. NettyRawTcpSocket 寫入增強 (`NettyRawTcpSocket.kt`)

**增強的連線狀態檢查：**
```kotlin
// 寫入前的全面狀態檢查
if (!ch.isOpen) {
    Log.w(TAG, "Attempted to write to closed channel ${ch.remoteAddress()}")
    throw IllegalStateException("Socket channel is closed")
}
if (!ch.isActive) {
    Log.w(TAG, "Attempted to write to inactive channel ${ch.remoteAddress()}")
    throw IllegalStateException("Socket is not active for writing.")
}
if (!ch.isWritable) {
    Log.w(TAG, "Channel ${ch.remoteAddress()} is not writable, may be congested")
}
```

**SSL 握手資料日誌：**
```kotlin
// 記錄小資料包的十六進位內容（可能是握手資料）
if (bytesToWrite <= 1024) {
    val bufferCopy = buffer.duplicate()
    val bytes = ByteArray(bytesToWrite)
    bufferCopy.get(bytes)
    val dataHex = bytes.joinToString(" ") { "%02x".format(it) }
    Log.v(TAG, "Data to $remoteAddr: $dataHex")
}
```

**增強的錯誤分析：**
```kotlin
when (cause) {
    is java.io.IOException -> {
        if (cause.message?.contains("Broken pipe") == true) {
            Log.e(TAG, "BROKEN PIPE: Remote side $remoteAddr closed connection during write")
        } else if (cause.message?.contains("Connection reset") == true) {
            Log.e(TAG, "CONNECTION RESET: Remote side $remoteAddr reset connection")
        }
    }
}
```

### 2. Tunnel Relay 流量分析 (`Tunnel.kt`)

**詳細的資料流追蹤：**
```kotlin
var totalBytesRelayed = 0
var packetCount = 0

// 記錄每個資料包的詳細資訊
packetCount++
totalBytesRelayed += bytesRead
Log.v(TAG, "Relay $name: read $bytesRead bytes (packet #$packetCount, total: $totalBytesRelayed).")

// SSL 握手分析：記錄前5個資料包的十六進位內容
if (packetCount <= 5 && bytesRead <= 512) {
    buffer.flip()
    val dataBytes = ByteArray(bytesRead)
    buffer.duplicate().get(dataBytes)
    val dataHex = dataBytes.joinToString(" ") { "%02x".format(it) }
    Log.d(TAG, "Relay $name packet #$packetCount data: $dataHex")
    buffer.rewind()
}
```

**寫入操作詳細追蹤：**
```kotlin
var remainingToWrite = bytesRead
var writeAttempts = 0
while (buffer.hasRemaining() && isActive && remainingToWrite > 0) {
    writeAttempts++
    val bytesWritten = try {
        destination.write(buffer)
    } catch (e: Exception) {
        Log.e(TAG, "Relay $name: Write failed on attempt $writeAttempts for packet #$packetCount: ${e.message}", e)
        throw e
    }
    
    remainingToWrite -= bytesWritten
    Log.v(TAG, "Relay $name: wrote $bytesWritten bytes (attempt $writeAttempts, remaining: $remainingToWrite).")
}
```

### 3. SOCKS5 時序診斷 (`Socks5ProxySocket.kt`)

**respondToSuccess() 時序追蹤：**
```kotlin
override suspend fun respondToSuccess() {
    val currentTime = System.currentTimeMillis()
    Log.d(TAG, "SOCKS5: [TIMING] Responding SUCCEEDED to ${remoteAddress} at timestamp $currentTime")
    Log.d(TAG, "SOCKS5: [TIMING] Client socket state - isOpen: ${clientSocket.isOpen}, localAddr: ${clientSocket.localAddress}")
    
    try {
        sendReply(REP_SUCCEEDED, localAddress, Port( (clientSocket.localAddress as? InetSocketAddress)?.port ?: 0))
        Log.d(TAG, "SOCKS5: [TIMING] SUCCESS reply sent to ${remoteAddress} successfully")
    } catch (e: Exception) {
        Log.e(TAG, "SOCKS5: [TIMING] Failed to send SUCCESS reply to ${remoteAddress}: ${e.message}", e)
        throw e
    }
}
```

**回應資料包詳細日誌：**
```kotlin
// 記錄回應資料包的十六進位內容
val replyBytes = ByteArray(replyBuffer.remaining())
replyBuffer.duplicate().get(replyBytes)
val replyHex = replyBytes.joinToString(" ") { "%02x".format(it) }
Log.d(TAG, "SOCKS5: [TIMING] Sending reply packet to ${remoteAddress}: $replyHex")

val writeStartTime = System.currentTimeMillis()
try {
    val bytesWritten = clientSocket.write(replyBuffer)
    val writeEndTime = System.currentTimeMillis()
    Log.d(TAG, "SOCKS5: [TIMING] Reply write completed to ${remoteAddress}: $bytesWritten bytes in ${writeEndTime - writeStartTime}ms")
} catch (e: Exception) {
    val writeEndTime = System.currentTimeMillis()
    Log.e(TAG, "SOCKS5: [TIMING] Reply write failed to ${remoteAddress} after ${writeEndTime - writeStartTime}ms: ${e.message}", e)
    throw e
}
```

## 測試和驗證

### 測試指令：
```bash
curl --socks5 10.0.0.98:1080 https://www.google.com
```

### 預期的診斷輸出：
1. **SOCKS5 時序日誌**：詳細的握手和回應時序
2. **SSL 握手資料**：前幾個資料包的十六進位內容
3. **連線狀態追蹤**：每次寫入前的詳細狀態檢查
4. **錯誤分析**：具體的 broken pipe 和 connection reset 分析

## 下一步行動

1. **部署診斷版本**並重新測試
2. **分析新的日誌輸出**，特別關注：
   - SSL 握手資料的完整性
   - SOCKS5 成功回應的時序
   - 連線中斷的確切時機
3. **根據診斷結果**制定具體的修復方案

這些改進將幫助我們精確定位 SSL 握手過程中的競態條件和連線管理問題。