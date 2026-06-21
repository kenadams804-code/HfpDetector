package com.example.hfpdetector

import android.Manifest
import android.app.Activity
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
            title = "通知（用于来电全屏弹出/通知）",
            isOn = { NotificationManagerCompat.from(this).areNotificationsEnabled() },
            onEnable = { openNotificationSettings() },
            onDisable = { openNotificationSettings() }
        )

        addRuntimePermRow("麦克风 RECORD_AUDIO（用于内网通话）", Manifest.permission.RECORD_AUDIO)
        addRuntimePermRow("相机 CAMERA（用于扫码配对）", Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= 31) {
            addRuntimePermRow("蓝牙连接 BLUETOOTH_CONNECT（用于HFP检测）", Manifest.permission.BLUETOOTH_CONNECT)
        }

        addCallScreeningRow()

        // 配对入口（不再用 isOn={true} 这种写法）
        addActionRow("打开配对页（有卡机：填IP/扫码）") {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        addActionRow("显示二维码（无卡机）") {
            startActivity(Intent(this, ShowQrActivity::class.java))
        }

        addPermRow(
            title = "蓝牙耳机模式检测（HFP 支持性）",
            isOn = { Prefs.getHfpSupport(this) == "YES" },
            onEnable = {
                if (!HfpCompat.canRunCheck(this)) {
                    toast("请先打开蓝牙，并授予蓝牙连接权限")
                    return@addPermRow
                }
                Prefs.setHfpSupport(this, "UNKNOWN")
                toast("检测中…约5秒")
                HfpCompat.checkAsync(this) {
                    toast("检测结果：$it（YES=支持，NO=不支持）")
                    render()
                }
            },
            onDisable = {
                Prefs.setHfpSupport(this, "UNKNOWN")
                toast("已清除检测结果")
                render()
            }
        )

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
            val on = isOn()
            text = if (on) "状态：开" else "状态：关"
        }

        val btn = Button(this).apply {
            val on = isOn()
            text = if (on) "去关闭（系统）" else "去开启"
            setOnClickListener {
                if (isOn()) onDisable() else onEnable()
            }
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

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }
}
