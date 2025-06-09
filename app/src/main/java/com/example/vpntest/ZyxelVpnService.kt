package com.example.vpntest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.vpntest.core.*
import com.example.vpntest.hev.ConfigManager
import com.example.vpntest.hev.HevTunnelManager
import com.example.vpntest.hev.TunnelMonitor
import com.example.vpntest.migration.MigrationFlags
import com.example.vpntest.network.VpnNetworkManager
import com.example.vpntest.protocols.TcpHandler
import com.example.vpntest.proxy.Socks5Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Zyxel VPN service using modular architecture
 * This demonstrates the new clean, extensible design
 */
class ZyxelVpnService : VpnService() {
    
    companion object {
        private const val TAG = "ZyxelVpnService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "ZYXEL_VPN_CHANNEL"
        
        const val ACTION_START_VPN = "START_ZYXEL_VPN"
        const val ACTION_STOP_VPN = "STOP_ZYXEL_VPN"
    }
    
    private val binder = ZyxelVpnBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Core components
    private lateinit var networkManager: VpnNetworkManager
    private lateinit var sessionManager: DefaultSessionManager
    private lateinit var connectionRouter: DefaultConnectionRouter
    private lateinit var packetProcessor: VpnPacketProcessor
    
    // Protocol handlers
    private lateinit var tcpHandler: TcpHandler
    
    // Proxy implementations
    private lateinit var socks5Proxy: Socks5Proxy
    
    // HEV tunnel components (第二階段新增)
    private lateinit var hevTunnelManager: HevTunnelManager
    private lateinit var configManager: ConfigManager
    private lateinit var tunnelMonitor: TunnelMonitor
    
    @Volatile
    private var isRunning = false
    
    inner class ZyxelVpnBinder : Binder() {
        fun getService(): ZyxelVpnService = this@ZyxelVpnService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeComponents()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VPN -> startVpn()
            ACTION_STOP_VPN -> stopVpn()
        }
        return START_STICKY
    }
    
    fun startVpn() {
        if (isRunning) {
            Log.d(TAG, "Zyxel VPN already running")
            return
        }
        
        serviceScope.launch {
            try {
                Log.i(TAG, "Starting Zyxel VPN service...")
                
                // Check VPN permission
                val vpnIntent = prepare(this@ZyxelVpnService)
                if (vpnIntent != null) {
                    Log.e(TAG, "VPN permission not granted")
                    return@launch
                }
                
                // 根據 MigrationFlags 選擇啟動方式
                if (MigrationFlags.USE_HEV_TUNNEL) {
                    startVpnWithHevTunnel()
                } else {
                    startVpnWithLegacyProcessor()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Zyxel VPN", e)
                stopVpn()
            }
        }
    }
    
    /**
     * 使用 hev-socks5-tunnel 啟動 VPN (第二階段新實作)
     */
    private suspend fun startVpnWithHevTunnel() {
        Log.i(TAG, "Starting VPN with hev-socks5-tunnel...")
        
        // 1. Setup network interface
        if (!networkManager.setupVpnInterface()) {
            Log.e(TAG, "Failed to setup VPN interface")
            return
        }
        
        // 2. Start SOCKS5 proxy
        socks5Proxy.start()
        connectionRouter.setDefaultProxy(socks5Proxy)
        
        // 3. Generate tunnel configuration
        val configPath = configManager.generateConfig(1080)
        val tunFd = getTunFileDescriptor()
        
        if (tunFd == -1) {
            Log.e(TAG, "Invalid TUN file descriptor")
            return
        }
        
        // 4. Start hev-tunnel
        if (!hevTunnelManager.startTunnel(tunFd, configPath)) {
            Log.e(TAG, "Failed to start hev-tunnel")
            return
        }
        
        // 5. Setup monitoring with restart callback
        tunnelMonitor.setRestartCallback { restartTunnel() }
        tunnelMonitor.startMonitoring()
        
        // 6. Monitor tunnel status changes
        tunnelMonitor.status
            .onEach { status ->
                Log.d(TAG, "Tunnel status changed: $status")
                when (status) {
                    com.example.vpntest.hev.TunnelStatus.FAILED -> {
                        Log.e(TAG, "Tunnel failed, stopping VPN")
                        stopVpn()
                    }
                    else -> {
                        // 其他狀態處理
                    }
                }
            }
            .launchIn(serviceScope)
        
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification("VPN Connected (HEV Tunnel)"))
        Log.i(TAG, "VPN started successfully with hev-tunnel")
        logSystemStatus()
    }
    
    /**
     * 使用舊的封包處理器啟動 VPN (保留作為備用方案)
     */
    private suspend fun startVpnWithLegacyProcessor() {
        Log.i(TAG, "Starting VPN with legacy packet processor...")
        
        // 1. Setup network interface
        if (!networkManager.setupVpnInterface()) {
            Log.e(TAG, "Failed to setup VPN interface")
            return
        }
        
        // 2. Create packet processor now that VPN interface is ready
        createPacketProcessor()
        
        // 3. Start SOCKS5 proxy
        socks5Proxy.start()
        
        // 4. Register proxy with router
        connectionRouter.setDefaultProxy(socks5Proxy)
        
        // 5. Register protocol handlers
        packetProcessor.registerHandler(tcpHandler)
        
        // 6. Start packet processing
        packetProcessor.start()
        
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification("VPN Connected (Legacy)"))
        Log.i(TAG, "VPN started successfully with legacy processor")
        logSystemStatus()
    }
    
    /**
     * 重啟 tunnel (用於監控器的自動重啟)
     */
    private suspend fun restartTunnel(): Boolean {
        return try {
            Log.i(TAG, "Restarting tunnel...")
            
            // 停止現有的 tunnel
            hevTunnelManager.stopTunnel()
            delay(1000)
            
            // 重新獲取 TUN fd 和配置
            val tunFd = getTunFileDescriptor()
            val configPath = configManager.getConfigPath()
            
            if (tunFd == -1) {
                Log.e(TAG, "Invalid TUN fd during restart")
                return false
            }
            
            // 重新啟動 tunnel
            val success = hevTunnelManager.startTunnel(tunFd, configPath)
            if (success) {
                Log.i(TAG, "Tunnel restarted successfully")
            } else {
                Log.e(TAG, "Failed to restart tunnel")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception during tunnel restart", e)
            false
        }
    }
    
    /**
     * 獲取 TUN 介面的檔案描述符
     */
    private fun getTunFileDescriptor(): Int {
        return try {
            val vpnInterface = networkManager.getVpnInterface()
            vpnInterface?.fd ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get TUN file descriptor", e)
            -1
        }
    }
    
    fun stopVpn() {
        if (!isRunning) return
        
        Log.i(TAG, "Stopping Zyxel VPN service...")
        isRunning = false
        
        try {
            if (MigrationFlags.USE_HEV_TUNNEL) {
                // 停止 HEV tunnel 相關組件
                tunnelMonitor.stopMonitoring()
                hevTunnelManager.stopTunnel()
            } else {
                // 停止舊的封包處理器
                if (::packetProcessor.isInitialized) {
                    packetProcessor.stop()
                }
                
                // 停止協議處理器
                if (::tcpHandler.isInitialized) {
                    tcpHandler.stop()
                }
                
                // 清理會話
                sessionManager.cleanup()
            }
            
            // 停止代理伺服器
            socks5Proxy.stop()
            
            // 清理網路介面
            networkManager.teardownVpnInterface()
            
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "Zyxel VPN service stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Zyxel VPN", e)
        }
    }
    
    fun isVpnRunning(): Boolean = isRunning
    
    /**
     * Get comprehensive system status
     */
    fun getSystemStatus(): String {
        return buildString {
            appendLine("=== Zyxel VPN System Status ===")
            appendLine("VPN Running: $isRunning")
            appendLine("Using HEV Tunnel: ${MigrationFlags.USE_HEV_TUNNEL}")
            appendLine()
            
            if (MigrationFlags.USE_HEV_TUNNEL) {
                appendLine("=== HEV Tunnel Status ===")
                if (::hevTunnelManager.isInitialized) {
                    appendLine("Tunnel Running: ${hevTunnelManager.isRunning()}")
                }
                if (::tunnelMonitor.isInitialized) {
                    appendLine("Tunnel Status: ${tunnelMonitor.status.value}")
                }
                appendLine()
            } else {
                appendLine("=== Legacy Processor Status ===")
                if (::packetProcessor.isInitialized) {
                    appendLine(packetProcessor.getStats())
                }
                appendLine()
                
                appendLine(sessionManager.getDetailedStats())
                appendLine()
            }
            
            appendLine(networkManager.getNetworkInfo())
            appendLine()
            
            appendLine(connectionRouter.getRoutingInfo())
            appendLine()
            
            val proxyStats = socks5Proxy.getStats()
            appendLine("=== SOCKS5 Proxy Stats ===")
            appendLine("Healthy: ${socks5Proxy.isHealthy()}")
            appendLine("Connections: ${proxyStats.connectionCount}")
            appendLine("Bytes transferred: ${proxyStats.bytesTransferred}")
            appendLine("Errors: ${proxyStats.errorCount}")
            appendLine("Avg response time: ${proxyStats.avgResponseTime}ms")
        }
    }
    
    /**
     * Test a specific connection routing
     */
    fun testConnectionRouting(host: String, port: Int): String {
        return connectionRouter.testConnection(host, port)
    }
    
    private fun initializeComponents() {
        // Initialize core components
        networkManager = VpnNetworkManager(this, this)
        sessionManager = DefaultSessionManager()
        connectionRouter = DefaultConnectionRouter()
        
        // Initialize proxy
        socks5Proxy = Socks5Proxy(this, 1080)
        
        // Initialize HEV tunnel components (第二階段新增)
        if (MigrationFlags.USE_HEV_TUNNEL) {
            hevTunnelManager = HevTunnelManager()
            configManager = ConfigManager(this)
            tunnelMonitor = TunnelMonitor(hevTunnelManager)
        }
        
        Log.d(TAG, "Components initialized (HEV Tunnel: ${MigrationFlags.USE_HEV_TUNNEL})")
    }
    
    private fun createPacketProcessor() {
        packetProcessor = VpnPacketProcessor(
            context = this,
            networkManager = networkManager,
            sessionManager = sessionManager,
            connectionRouter = connectionRouter
        )
        
        // Initialize protocol handlers
        tcpHandler = TcpHandler(
            context = this,
            networkManager = networkManager,
            sessionManager = sessionManager,
            connectionRouter = connectionRouter,
            packetProcessor = packetProcessor
        )
    }
    
    private fun logSystemStatus() {
        Log.d(TAG, "System status at startup:")
        val status = getSystemStatus()
        status.lines().forEach { line ->
            Log.d(TAG, line)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zyxel VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Zyxel VPN connection status"
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zyxel VPN Service")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        
        // 清理 HEV tunnel 組件
        if (MigrationFlags.USE_HEV_TUNNEL) {
            if (::tunnelMonitor.isInitialized) {
                tunnelMonitor.cleanup()
            }
            if (::hevTunnelManager.isInitialized) {
                hevTunnelManager.cleanup()
            }
        }
        
        serviceScope.cancel()
        Log.d(TAG, "ZyxelVpnService destroyed")
    }
    
    /**
     * Demonstrate adding a new routing rule at runtime
     */
    fun addCustomRoutingRule(id: String, pattern: String, action: RoutingAction, proxyType: String? = null) {
        val rule = RoutingRule(
            id = id,
            pattern = pattern,
            action = action,
            proxyType = proxyType,
            priority = 75 // Between default rules and catch-all
        )
        connectionRouter.addRule(rule)
        Log.i(TAG, "Added custom routing rule: $id")
    }
    
    /**
     * Demonstrate removing a routing rule
     */
    fun removeCustomRoutingRule(id: String) {
        connectionRouter.removeRule(id)
        Log.i(TAG, "Removed custom routing rule: $id")
    }
    
    /**
     * Get real-time statistics
     */
    fun getRealtimeStats(): SessionStats {
        return if (MigrationFlags.USE_HEV_TUNNEL) {
            // HEV tunnel 模式下，統計資訊主要來自代理伺服器
            SessionStats(
                totalSessions = 0, // HEV tunnel 不維護會話
                activeSessions = 0,
                bytesTransferred = socks5Proxy.getStats().bytesTransferred,
                packetsProcessed = 0,
                errorsCount = socks5Proxy.getStats().errorCount.toLong()
            )
        } else {
            sessionManager.getStats()
        }
    }
    
    /**
     * Get active sessions by protocol
     */
    fun getActiveSessionsByProtocol(protocol: Int): List<VpnSession> {
        return if (MigrationFlags.USE_HEV_TUNNEL) {
            // HEV tunnel 模式下不維護詳細會話資訊
            emptyList()
        } else {
            sessionManager.getSessionsByProtocol(protocol)
        }
    }
    
    /**
     * 獲取 HEV tunnel 特定的狀態資訊
     */
    fun getHevTunnelInfo(): String? {
        return if (MigrationFlags.USE_HEV_TUNNEL && ::configManager.isInitialized) {
            buildString {
                appendLine("=== HEV Tunnel Configuration ===")
                appendLine("Config Path: ${configManager.getConfigPath()}")
                appendLine("Log Path: ${configManager.getLogPath()}")
                if (::hevTunnelManager.isInitialized) {
                    appendLine("Tunnel Running: ${hevTunnelManager.isRunning()}")
                }
                if (::tunnelMonitor.isInitialized) {
                    appendLine("Monitor Status: ${tunnelMonitor.status.value}")
                }
            }
        } else {
            null
        }
    }
}
