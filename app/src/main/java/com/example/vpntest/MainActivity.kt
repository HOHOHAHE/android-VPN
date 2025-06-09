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

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var vpnToggleButton: Button
    private lateinit var statusText: TextView
    private var vpnService: ZyxelVpnService? = null
    private var isServiceBound = false
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        statusText = findViewById(R.id.statusText)

        vpnToggleButton.setOnClickListener {
            toggleVpn()
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

    private fun bindVpnService() {
        val intent = Intent(this, ZyxelVpnService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI() {
        val service = vpnService
        val isRunning = service?.isVpnRunning() ?: false

        vpnToggleButton.text = if (isRunning) "Disconnect VPN" else "Connect VPN"
        statusText.text = if (isRunning) {
            "Zyxel VPN Status: Connected\nSOCKS5 Server: 127.0.0.1:1080"
        } else {
            "Zyxel VPN Status: Disconnected"
        }

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
