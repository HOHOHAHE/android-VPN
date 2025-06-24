# SOCKS5 連接問題調試報告

## 問題描述
SOCKS5 代理伺服器在處理客戶端連接時遇到「Connection closed by client while reading」錯誤，導致握手失敗。

## 診斷過程

### 1. 問題分析
基於錯誤日誌，識別了 5-7 個可能的問題來源：
1. **SOCKS5 握手協議實現問題** - 伺服器端握手處理邏輯錯誤
2. **客戶端連接行為異常** - 客戶端建立連接後立即斷開
3. **網絡超時或連接中斷** - 網絡不穩定導致連接提前關閉
4. **緩衝區讀取問題** - `readBytes` 方法實現有問題
5. **Netty 通道狀態管理問題** - 底層網絡通道狀態異常
6. **併發處理問題** - 多線程環境下的資源競爭
7. **VPN 路由配置問題** - VPN 路由導致代理連接異常

### 2. 診斷日誌添加
在關鍵位置添加了詳細的調試日誌：
- [`NettyProxyServer.kt`](../app/src/main/io/github/hohohahe/nekitkotlin/proxyserver/NettyProxyServer.kt) - 連接接受和處理時機
- [`Socks5ProxySocket.kt`](../app/src/main/io/github/hohohahe/nekitkotlin/socket/proxy/Socks5ProxySocket.kt) - SOCKS5 握手狀態檢查
- [`NettyRawTcpSocket.kt`](../app/src/main/io/github/hohohahe/nekitkotlin/socket/raw/NettyRawTcpSocket.kt) - 底層 Socket 狀態變化

### 3. 根本原因確認
通過診斷日誌確認了問題的真正原因：

**時間軸分析（2025-06-24 14:55:03）：**
```
14:55:03.661 - NettyProxyServer 接受新連接：/10.0.0.98:47400 ✅
14:55:03.672 - 初始狀態檢查：isActive=true, isOpen=true ✅
14:55:03.674 - 關鍵時刻：Channel 變為 inactive：isActive=false ❌
14:55:03.724 - 50ms 延遲後：連接已經關閉
14:55:03.737 - SOCKS5 握手嘗試：socket 已經關閉
```

**根本原因：**
客戶端在 TCP 連接建立後約 10-12ms 內立即斷開連接，這發生在 SOCKS5 握手開始之前。這不是 SOCKS5 協議問題，而是**連接時機和穩定性問題**。

## 解決方案

### 1. 連接穩定性檢查
在 `NettyProxyServer.kt` 中添加了連接穩定性檢查：
```kotlin
// 等待連接穩定，避免處理立即斷開的連接
kotlinx.coroutines.delay(100) // 給客戶端時間發送數據或穩定連接

// 檢查連接在穩定期後是否仍然活躍
if (!ch.isActive || !ch.isOpen || !rawTcpSocketForClient.isOpen) {
    Log.w(TAG, "Client connection ${ch.remoteAddress()} closed during stabilization period")
    return@launch // 提早退出，資源會在 finally 塊中清理
}
```

### 2. 簡化錯誤處理
- 移除了冗餘的診斷日誌
- 簡化了 SOCKS5 握手前的檢查邏輯
- 改進了錯誤訊息的清晰度

### 3. 改進的錯誤處理流程
```
1. 接受連接
2. 等待 100ms 確保連接穩定
3. 檢查連接狀態
4. 如果連接已關閉，優雅退出
5. 如果連接穩定，開始 SOCKS5 握手
```

## 技術細節

### 修改的文件
1. **NettyProxyServer.kt**
   - 添加連接穩定性檢查
   - 改進早期連接斷開的處理

2. **Socks5ProxySocket.kt**
   - 簡化握手前的狀態檢查
   - 改進錯誤訊息

3. **NettyRawTcpSocket.kt**
   - 清理調試日誌
   - 保留關鍵錯誤處理

### 預期效果
- 減少因客戶端早期斷開導致的錯誤日誌
- 改善 SOCKS5 代理的穩定性
- 提供更清晰的錯誤診斷資訊

## 測試建議
1. 運行修復後的應用程式
2. 觀察 SOCKS5 連接行為
3. 確認早期斷開的連接被優雅處理
4. 驗證正常的 SOCKS5 握手仍然正常工作

## 後續監控
- 監控「Waiting for connection to stabilize」日誌
- 觀察是否還有連接在穩定期後立即斷開
- 如果問題持續，可能需要調整穩定期時間或添加更多診斷