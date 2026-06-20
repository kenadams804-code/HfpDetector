package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var tv: TextView

    private lateinit var swSilence: Switch
    private lateinit var btnPerm: Button
    private lateinit var btnStart: Button
    private lateinit var btnRole: Button
    private lateinit var btnLog: Button

    private lateinit var btnShowQr: Button
    private lateinit var btnPair: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply {
            textSize = 16f
            setPadding(40, 50, 40, 10)
        }

        swSilence = Switch(this).apply {
            text = "有卡手机来电尽量静音（主要让无卡手机响）"
            isChecked = Prefs.isSilencePstn(this@MainActivity)
        }

        btnPerm = Button(this).apply { text = "申请权限（通知/麦克风）" }
        btnStart = Button(this).apply { text = "启动常驻服务（两台手机都点一次）" }
        btnRole = Button(this).apply { text = "（仅有卡手机）开启来电号码同步（系统授权）" }
        btnLog = Button(this).apply { text = "打开日志页（查看全部运行信息）" }

        btnShowQr = Button(this).apply { text = "（无卡手机）显示配对二维码" }
        btnPair = Button(this).apply { text = "（有卡手机）手动配对（输入IP/扫码）" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(swSilence)
            addView(btnPerm)
            addView(btnStart)
            addView(btnRole)
            addView(btnPair)
            addView(btnShowQr)
            addView(btnLog)
        }
        setContentView(root)

        swSilence.setOnCheckedChangeListener { _, checked ->
            Prefs.setSilencePstn(this, checked)
            AppLog.i(this, "设置：有卡机来电尽量静音=$checked")
            refreshUi()
        }

        btnPerm.setOnClickListener { requestRuntimePermissions() }
        btnStart.setOnClickListener {
            CoreService.start(this)
            AppLog.i(this, "手动启动 CoreService")
        }

        btnRole.setOnClickListener { requestCallScreeningRole() }

        btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        btnShowQr.setOnClickListener {
            startActivity(Intent(this, ShowQrActivity::class.java))
        }

        btnPair.setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }

        // 默认启动（你要求“立即触发”）
        CoreService.start(this)
        refreshUi()
    }

    private fun refreshUi() {
        val tm = getSystemService(TelephonyManager::class.java)
        val hasSimReady = tm?.simState == TelephonyManager.SIM_STATE_READY

        val roleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else false

        swSilence.visibility = if (hasSimReady) View.VISIBLE else View.GONE
        btnRole.visibility = if (hasSimReady) View.VISIBLE else View.GONE
        btnPair.visibility = if (hasSimReady) View.VISIBLE else View.GONE
        btnShowQr.visibility = if (hasSimReady) View.GONE else View.VISIBLE

        val manualPair = Prefs.isManualPairEnabled(this)
        val peerIp = Prefs.getPeerIp(this)

        tv.text = buildString {
            append("LanCall 已启动（后台常驻工作中）\n")
            if (hasSimReady) {
                append("本机：有卡手机\n")
                append(if (roleHeld) "来电号码同步：已开启\n" else "来电号码同步：未开启（点按钮开启）\n")
                append("手动配对：${if (manualPair) "启用" else "未启用"}\n")
                append("接听端 IP：${if (peerIp.isBlank()) "未设置（可扫码）" else peerIp}\n")
            } else {
                append("本机：无卡手机（接听端）\n")
                val ip = NetUtils.getLocalWifiIp(this@MainActivity)
                append("本机 Wi‑Fi IP：${if (ip.isBlank()) "未获取到" else ip}\n")
            }
            append("\n详细运行信息请进“日志页”查看。")
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 2001)
        }
    }

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) return

        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 3001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshUi()
    }
}
