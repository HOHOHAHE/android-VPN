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
import com.example.vpntest.network.VpnNetworkManager
import com.example.vpntest.protocols.TcpHandler
import com.example.vpntest.proxy.Socks5Proxy
import kotlinx.coroutines.*

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
                
                // 1. Setup network interface
                if (!networkManager.setupVpnInterface()) {
                    Log.e(TAG, "Failed to setup VPN interface")
                    return@launch
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
                startForeground(NOTIFICATION_ID, createNotification("Zyxel VPN Connected"))
                
                Log.i(TAG, "Zyxel VPN service started successfully")
                logSystemStatus()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Zyxel VPN", e)
                stopVpn()
            }
        }
    }
    
    fun stopVpn() {
        if (!isRunning) return
        
        Log.i(TAG, "Stopping Zyxel VPN service...")
        isRunning = false
        
        try {
            // Stop packet processing
            packetProcessor.stop()
            
            // Stop protocol handlers
            tcpHandler.stop()
            
            // Stop proxy
            socks5Proxy.stop()
            
            // Cleanup sessions
            sessionManager.cleanup()
            
            // Teardown network
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
            appendLine()
            
            appendLine(networkManager.getNetworkInfo())
            appendLine()
            
            appendLine(sessionManager.getDetailedStats())
            appendLine()
            
            appendLine(packetProcessor.getStats())
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
        
        Log.d(TAG, "Components initialized")
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
        return sessionManager.getStats()
    }
    
    /**
     * Get active sessions by protocol
     */
    fun getActiveSessionsByProtocol(protocol: Int): List<VpnSession> {
        return sessionManager.getSessionsByProtocol(protocol)
    }
}
