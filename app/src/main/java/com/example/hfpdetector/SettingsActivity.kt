package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

class SettingsActivity : Activity() {

    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "设置 / 授权"
            textSize = 20f
            setPadding(30, 40, 30, 20)
        }

        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 30)
        }

        val scroll = ScrollView(this).apply { addView(box) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        box.removeAllViews()

        addPermRow(
            title = "通知总开关（来电弹窗依赖）",
            isOn = { NotificationManagerCompat.from(this).areNotificationsEnabled() },
            onEnable = { openNotificationSettings() },
            onDisable = { openNotificationSettings() }
        )

        // 新增：全屏通知（Android 14+ 可能被系统单独关掉）
        addPermRow(
            title = "允许全屏来电弹窗（系统开关，Android 14+）",
            isOn = { canUseFullScreenIntentCompat() },
            onEnable = { openFullScreenIntentSettings() },
            onDisable = { openFullScreenIntentSettings() }
        )

        // 新增：直接打开“来电通知通道”设置，用户可手动调到“高/允许悬浮/锁屏/全屏”
        addActionRow("来电通知通道设置（重要性/悬浮/锁屏/全屏）") {
            openCallChannelSettings()
        }

        addRuntimePermRow("麦克风 RECORD_AUDIO（用于内网通话）", Manifest.permission.RECORD_AUDIO)
        addRuntimePermRow("相机 CAMERA（用于扫码配对）", Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= 31) {
            addRuntimePermRow("蓝牙连接 BLUETOOTH_CONNECT（用于HFP检测）", Manifest.permission.BLUETOOTH_CONNECT)
        }

        addCallScreeningRow()

        addActionRow("打开配对页（有卡机：填IP/扫码）") {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        addActionRow("显示二维码（无卡机）") {
            startActivity(Intent(this, ShowQrActivity::class.java))
        }

        addActionRow("打开系统日志页") {
            startActivity(Intent(this, LogActivity::class.java))
        }

        addActionRow("打开应用系统设置（统一管理权限/后台/自启动）") {
            openAppDetailsSettings()
        }
    }

    private fun addRuntimePermRow(title: String, perm: String) {
        addPermRow(
            title = title,
            isOn = { hasPerm(perm) },
            onEnable = { if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(perm), 9001) },
            onDisable = { openAppDetailsSettings() }
        )
    }

    private fun addCallScreeningRow() {
        val tm = getSystemService(TelephonyManager::class.java)
        val hasSim = tm?.simState == TelephonyManager.SIM_STATE_READY

        addPermRow(
            title = "来电号码同步（Call Screening，仅有卡机需要）",
            isOn = {
                if (!hasSim) return@addPermRow false
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@addPermRow false
                val rm = getSystemService(RoleManager::class.java)
                rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
            },
            onEnable = {
                if (!hasSim) { toast("无卡机不需要此项"); return@addPermRow }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { toast("系统版本不支持"); return@addPermRow }
                val rm = getSystemService(RoleManager::class.java) ?: return@addPermRow
                if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) { toast("系统不提供该角色"); return@addPermRow }
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 9101)
            },
            onDisable = {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        )
    }

    private fun addPermRow(
        title: String,
        isOn: () -> Boolean,
        onEnable: () -> Unit,
        onDisable: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 18, 0, 18)
        }

        val tv = TextView(this).apply {
            textSize = 16f
            text = title
        }

        val st = TextView(this).apply {
            textSize = 12f
            text = if (isOn()) "状态：开" else "状态：关"
        }

        val btn = Button(this).apply {
            text = if (isOn()) "去关闭（系统）" else "去开启"
            setOnClickListener { if (isOn()) onDisable() else onEnable() }
        }

        row.addView(tv)
        row.addView(st)
        row.addView(btn)
        box.addView(row)
    }

    private fun addActionRow(title: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 18, 0, 18)
        }
        val tv = TextView(this).apply {
            textSize = 16f
            text = title
        }
        val btn = Button(this).apply {
            text = "打开"
            setOnClickListener { action() }
        }
        row.addView(tv)
        row.addView(btn)
        box.addView(row)
    }

    private fun hasPerm(p: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED else true
    }

    private fun openAppDetailsSettings() {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(i)
    }

    private fun openNotificationSettings() {
        val i = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(i)
    }

    private fun openCallChannelSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, AppConfig.CH_CALL)
            }
            startActivity(i)
        } else {
            openNotificationSettings()
        }
    }

    private fun canUseFullScreenIntentCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.canUseFullScreenIntent() == true
            } catch (_: Throwable) {
                true
            }
        } else true
    }

    private fun openFullScreenIntentSettings() {
        // Android 14+ 的全屏通知开关页面（不同 ROM 可能会有差异）
        val action = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"
        val i = Intent(action).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(i) }
            .onFailure { openNotificationSettings() }
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }
}
