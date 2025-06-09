package com.example.vpntest

import com.example.vpntest.migration.MigrationFlags

object NativeBridge {
    init {
        // 載入原有的 native-lib 庫
        System.loadLibrary("native-lib")
        
        // 如果啟用 HEV Tunnel，也載入 hev-tunnel-bridge 庫
        if (MigrationFlags.shouldUseHevTunnel()) {
            try {
                System.loadLibrary("hev-tunnel-bridge")
            } catch (e: UnsatisfiedLinkError) {
                // 如果載入失敗，記錄錯誤但不中斷程式執行
                android.util.Log.e("NativeBridge", "Failed to load hev-tunnel-bridge", e)
            }
        }
    }

    // 原有的 JNI 方法
    external fun stringFromJNI(): String
    
    // 新增 HEV Tunnel 相關的 JNI 方法（透過 HevTunnelManager 呼叫）
    // 這些方法保留為備用，主要透過 HevTunnelManager 使用
}