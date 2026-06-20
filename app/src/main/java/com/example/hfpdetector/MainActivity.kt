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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvLast: TextView
    private lateinit var swSilence: Switch
    private lateinit var btnPerm: Button
    private lateinit var btnStart: Button
    private lateinit var btnRole: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tvTitle = TextView(this).apply {
            textSize = 20f
            setPadding(40, 50, 40, 10)
            text = "LanCall（局域网接听）"
        }

        tvSummary = TextView(this).apply {
            textSize = 16f
            setPadding(40, 10, 40, 10)
        }

        tvLast = TextView(this).apply {
            textSize = 14f
            setPadding(40, 10, 40, 20)
        }

        swSilence = Switch(this).apply {
            text = "有卡手机来电尽量静音（主要让无卡手机响）"
        }

        btnPerm = Button(this).apply { text = "申请权限（通知/麦克风）" }
        btnStart = Button(this).apply { text = "启动常驻服务（两台手机都点一次）" }
        btnRole = Button(this).apply { text = "（仅有卡手机）开启来电号码同步（系统授权）" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvTitle)
            addView(tvSummary)
            addView(tvLast)
            addView(swSilence)
            addView(btnPerm)
            addView(btnStart)
            addView(btnRole)
        }
        setContentView(root)

        btnPerm.setOnClickListener { requestRuntimePermissions() }
        btnStart.setOnClickListener { CoreService.start(this) }
        btnRole.setOnClickListener { requestCallScreeningRole() }

        swSilence.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setSilencePstn(this, isChecked)
            refreshUi()
        }

        // 你要求“立即触发”，默认启动常驻服务
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

        val syncText = if (hasSimReady) {
            if (roleHeld) "来电号码同步：已开启" else "来电号码同步：未开启（点下面按钮开启）"
        } else {
            "本机为无卡手机：只负责接听与响铃"
        }

        // 无卡机不需要“静音有卡机来电”这个开关
        swSilence.visibility = if (hasSimReady) View.VISIBLE else View.GONE
        swSilence.isChecked = Prefs.isSilencePstn(this)

        // 无卡机不需要申请“来电筛选角色”
        btnRole.visibility = if (hasSimReady) View.VISIBLE else View.GONE

        tvSummary.text = buildString {
            append(syncText)
            append("\n\n使用方法：")
            append("\n1）两台手机安装同一个 APK，并连接同一个 Wi‑Fi")
            append("\n2）两台手机都打开 App，点一次“启动常驻服务”")
            if (hasSimReady) {
                append("\n3）在有卡手机上点“开启来电号码同步（系统授权）”，按系统提示允许")
                append("\n4）外部来电时：无卡手机会弹出来电界面显示号码，点接听即可内网对讲")
            } else {
                append("\n3）等待有卡手机触发来电后，本机会弹出接听界面")
            }
        }

        val lastNumber = Prefs.getLastNumber(this)
        val lastTime = Prefs.getLastTime(this)
        tvLast.text = if (hasSimReady && roleHeld && lastTime > 0) {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            "最近一次收到系统来电回调：${sdf.format(Date(lastTime))}\n号码：$lastNumber"
        } else if (hasSimReady && roleHeld) {
            "最近一次收到系统来电回调：暂无（请用第三台手机打进来测试）"
        } else {
            ""
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
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
