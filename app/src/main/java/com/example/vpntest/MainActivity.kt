package com.example.vpntest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import io.github.hohohahe.nekitkotlin.rule.RuleManager
import io.github.hohohahe.nekitkotlin.rule.AllRule
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.DirectAdapterFactory
import io.github.hohohahe.nekitkotlin.proxyserver.NettyProxyServer
import io.github.hohohahe.nekitkotlin.proxyserver.ProxyType
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.core.IpAddress
import com.example.vpntest.proxy.Socks5Proxy
import java.net.NetworkInterface
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var vpnToggleButton: Button
    private lateinit var proxyServerToggleButton: Button
    private lateinit var statusText: TextView
    private var vpnService: ZyxelVpnService? = null
    private var isServiceBound = false
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // ProxyServer 相關變數 - 簡化版本
    private var isProxyServerRunning = false
    private var proxyServerJob: Job? = null
    private var proxyServer: NettyProxyServer? = null
    private var socks5Proxy: Socks5Proxy? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ZyxelVpnService.ZyxelVpnBinder
            vpnService = binder.getService()
            isServiceBound = true
            updateUI()
            Log.d(TAG, "Zyxel VPN service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            isServiceBound = false
            updateUI()
            Log.d(TAG, "Zyxel VPN service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupUI()
        bindVpnService()
    // 測試 JNI 連結
    try {
        val jniMsg = NativeBridge.stringFromJNI()
        Log.d(TAG, "JNI test: $jniMsg")
        // 若 statusText 已初始化則顯示
        if (::statusText.isInitialized) {
            statusText.text = "${statusText.text}\nJNI: $jniMsg"
        }
    } catch (e: Exception) {
        Log.e(TAG, "JNI test failed", e)
    }
    }

    private fun setupUI() {
        vpnToggleButton = findViewById(R.id.vpnToggleButton)
        proxyServerToggleButton = findViewById(R.id.proxyServerToggleButton)
        statusText = findViewById(R.id.statusText)

        vpnToggleButton.setOnClickListener {
            toggleVpn()
        }
        
        proxyServerToggleButton.setOnClickListener {
            toggleProxyServer()
        }

        // Start periodic UI updates
        startUIUpdates()
    }

    private fun toggleVpn() {
        val service = vpnService
        if (service == null) {
            Toast.makeText(this, "Zyxel VPN service not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (service.isVpnRunning()) {
            stopVpnService()
        } else {
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, ZyxelVpnService::class.java).apply {
                action = ZyxelVpnService.ACTION_START_VPN
            }
            startForegroundService(intent)
            Log.d(TAG, "Zyxel VPN start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Zyxel VPN service", e)
            Toast.makeText(this, "Failed to start Zyxel VPN", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpnService() {
        try {
            val intent = Intent(this, ZyxelVpnService::class.java).apply {
                action = ZyxelVpnService.ACTION_STOP_VPN
            }
            startService(intent)
            Log.d(TAG, "Zyxel VPN stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Zyxel VPN service", e)
            Toast.makeText(this, "Failed to stop Zyxel VPN", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleProxyServer() {
        if (isProxyServerRunning) {
            stopProxyServer()
        } else {
            startProxyServer()
        }
    }
    
    private fun getLanIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳過回環和非活動介面
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // 只取 IPv4 地址且不是回環地址
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        // 確保是私有網路 IP (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
                        if (ip != null && (ip.startsWith("192.168.") ||
                                         ip.startsWith("10.") ||
                                         ip.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")))) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get LAN IP address", e)
        }
        return null
    }
     
    private fun startProxyServer() {
        if (isProxyServerRunning) {
            Toast.makeText(this, "Proxy Server 已經在運行中", Toast.LENGTH_SHORT).show()
            return
        }
        
        mainScope.launch {
            try {
                // 獲取 LAN IP 地址
                val lanIp = getLanIpAddress()
                val bindAddress = if (lanIp != null) {
                    IpAddress(lanIp)
                } else {
                    Log.w(TAG, "Could not get LAN IP, binding to all interfaces")
                    null // 將綁定到 0.0.0.0 (所有介面)
                }
                
                // 創建 RuleManager 實例 (使用 AllRule 搭配 DirectAdapterFactory)
                val directAdapterFactory = DirectAdapterFactory()
                val allRule = AllRule(directAdapterFactory)
                val ruleManager = RuleManager(listOf(allRule))
                
                // 創建 SOCKS5 Proxy Server 在指定 IP 和 port 1080
                proxyServer = NettyProxyServer(
                    port = Port(1080),
                    host = bindAddress,
                    proxyType = ProxyType.SOCKS5,
                    ruleManager = ruleManager
                )
                
                // 啟動 ProxyServer
                proxyServer?.start()
                isProxyServerRunning = true
                
                val bindInfo = if (bindAddress != null) "${bindAddress.value}:1080" else "0.0.0.0:1080"
                Log.i(TAG, "=== SOCKS5 Proxy Server started on $bindInfo ===")
                Log.i(TAG, "Server is running: ${proxyServer?.isRunning()}")
                Log.i(TAG, "Waiting for SOCKS5 connections...")
                Toast.makeText(this@MainActivity, "SOCKS5 代理已啟動 ($bindInfo)", Toast.LENGTH_SHORT).show()
                updateUI()
                
                // 創建並啟動 Socks5Proxy server（端口 1081）
                try {
                    socks5Proxy = Socks5Proxy(this@MainActivity, 1081)
                    socks5Proxy?.start()
                    Log.i(TAG, "=== Additional Socks5Proxy server started on port 1081 ===")
                    Toast.makeText(this@MainActivity, "額外 SOCKS5 代理已啟動 (1081)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Socks5Proxy server", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Proxy Server", e)
                Toast.makeText(this@MainActivity, "無法啟動 Proxy Server: ${e.message}", Toast.LENGTH_LONG).show()
                isProxyServerRunning = false
                updateUI()
            }
        }
        
        // 延遲測試連接（給服務器一些時間啟動）
        mainScope.launch {
            delay(2000) // 等待 2 秒
            testProxyConnection()
        }
    }
    
    private suspend fun testProxyConnection() {
        try {
            val lanIp = getLanIpAddress()
            val testAddress = lanIp ?: "127.0.0.1"
            
            // 嘗試連接到代理端口
            withContext(Dispatchers.IO) {
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(testAddress, 1080), 5000)
                    socket.close()
                    
                    Log.i(TAG, "✅ SOCKS5 代理端口 $testAddress:1080 可以連接")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "✅ 代理端口測試成功", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "❌ 無法連接到代理端口 $testAddress:1080: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ 代理端口測試失敗", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "代理連接測試出錯", e)
        }
    }
    
    private fun stopProxyServer() {
        if (!isProxyServerRunning) {
            Toast.makeText(this, "Proxy Server 未在運行", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            proxyServer?.stop()
            proxyServer = null
            
            // 停止 Socks5Proxy server
            socks5Proxy?.stop()
            socks5Proxy = null
            
            isProxyServerRunning = false
            
            Log.d(TAG, "Proxy Server stopped")
            Toast.makeText(this, "Proxy Server 已停止", Toast.LENGTH_SHORT).show()
            updateUI()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Proxy Server", e)
            Toast.makeText(this, "停止 Proxy Server 時發生錯誤: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindVpnService() {
        val intent = Intent(this, ZyxelVpnService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI() {
        val service = vpnService
        val isVpnRunning = service?.isVpnRunning() ?: false

        // 更新 VPN 按鈕
        vpnToggleButton.text = if (isVpnRunning) "Disconnect VPN" else "Connect VPN"
        
        // 更新 ProxyServer 按鈕
        proxyServerToggleButton.text = if (isProxyServerRunning) "停止 Proxy Server" else "啟動 Proxy Server"
        
        // 更新狀態文字
        val vpnStatus = if (isVpnRunning) "Connected" else "Disconnected"
        val proxyStatus = if (isProxyServerRunning) {
            val lanIp = getLanIpAddress()
            val bindInfo = if (lanIp != null) "$lanIp:1080" else "0.0.0.0:1080"
            "Running ($bindInfo)"
        } else {
            "Stopped"
        }
        
        statusText.text = "Zyxel VPN Status: $vpnStatus\nProxy Server: $proxyStatus"

//        Log.d(TAG, "UI updated - Zyxel VPN running: $isRunning")
    }

    private fun startUIUpdates() {
        mainScope.launch {
            while (true) {
                updateUI()
                delay(1000) // Update every second
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 停止 ProxyServer
        if (isProxyServerRunning) {
            try {
                proxyServer?.stop()
                proxyServer = null
                isProxyServerRunning = false
                Log.d(TAG, "Proxy Server stopped in onDestroy")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Proxy Server in onDestroy", e)
            }
        }
        
        // 清理其他資源
        mainScope.cancel()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}
