package com.example.vpntest

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LauncherActivity : AppCompatActivity() {
    
    private lateinit var welcomeText: TextView
    private lateinit var enterMainButton: Button
    private lateinit var aboutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_launcher)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.launcher)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        initViews()
        setupClickListeners()
    }
    
    private fun initViews() {
        welcomeText = findViewById(R.id.welcomeText)
        enterMainButton = findViewById(R.id.enterMainButton)
        aboutButton = findViewById(R.id.aboutButton)
    }
    
    private fun setupClickListeners() {
        enterMainButton.setOnClickListener {
            // 啟動主要的 VPN 功能 Activity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            // 如果您希望回到此頁面時關閉，可以加上 finish()
            // finish()
        }
        
        aboutButton.setOnClickListener {
            // 可以在這裡添加關於頁面或其他功能
            // 目前暫時顯示簡單的訊息
            showAboutDialog()
        }
    }
    
    private fun showAboutDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("關於 VPN 應用程式")
            .setMessage("這是一個基於 hev-socks5-tunnel 的 Android VPN 應用程式。\n\n版本：1.0\n開發日期：2025年6月")
            .setPositiveButton("確定", null)
            .show()
    }
}
