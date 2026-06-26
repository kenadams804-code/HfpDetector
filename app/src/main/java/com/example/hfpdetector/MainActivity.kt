// app/src/main/java/com/example/hfpdetector/MainActivity.kt
package com.example.hfpdetector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var modeSpinner: Spinner
    private lateinit var modeAdapter: ModeAdapter
    private var hasSim = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasSim = Prefs.isHasSimReady(this) // 你可以自行实现或用 TelephonyManager 判断

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "LanCall - 局域网来电接听"
            textSize = 20f
            setTextColor(Color.BLACK)
        }

        modeAdapter = ModeAdapter(this, listOf("LAN 模式", "蓝牙 HFP 模式"))
        modeSpinner = Spinner(this).apply { adapter = modeAdapter }

        val btnLog = Button(this).apply { text = "日志 & 诊断" }
        val btnPair = Button(this).apply { text = "配对" }
        val btnTest = Button(this).apply { text = "发送测试来电" }

        root.addView(title)
        root.addView(modeSpinner)
        root.addView(btnLog)
        root.addView(btnPair)
        root.addView(btnTest)

        setContentView(root)

        btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        btnPair.setOnClickListener { startActivity(Intent(this, PairingActivity::class.java)) }
        btnTest.setOnClickListener { CoreService.sendTestInvite(this) }

        // 权限请求
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }
    }
}
