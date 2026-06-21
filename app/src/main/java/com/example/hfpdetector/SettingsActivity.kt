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
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        box.removeAllViews()

        // 通知（Android 13+ 才有运行时开关，但低版本也可能被系统关闭，所以统一用 areNotificationsEnabled 判断）
        addPermRow(
            title = "通知（用于来电全屏弹出/通知）",
            isOn = { NotificationManagerCompat.from(this).areNotificationsEnabled() },
            onEnable = { openNotificationSettings() },
            onDisable = { openNotificationSettings() }
        )

        addRuntimePermRow(
            title = "麦克风 RECORD_AUDIO（用于内网通话）",
            perm = Manifest.permission.RECORD_AUDIO
        )

        addRuntimePermRow(
            title = "接收短信 RECEIVE_SMS（用于同步收件箱）",
            perm = Manifest.permission.RECEIVE_SMS
        )

        addRuntimePermRow(
            title = "读取短信 READ_SMS（用于同步发件箱）",
            perm = Manifest.permission.READ_SMS
        )

        addRuntimePermRow(
            title = "相机 CAMERA（用于扫码配对）",
            perm = Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= 31) {
            addRuntimePermRow(
                title = "蓝牙连接 BLUETOOTH_CONNECT（用于蓝牙模式检测/蓝牙模式）",
                perm = Manifest.permission.BLUETOOTH_CONNECT
            )
        }

        addCallScreeningRow()

        // HFP 检测不是“授权”，但你希望也在设置里用一条可控项展示，这里按“开/关”的交互来做：
        // - 关：未检测/未知
        // - 开：已检测（YES/NO）
        val hfp = Prefs.getHfpSupport(this) // UNKNOWN/YES/NO
        val hfpOn = hfp != "UNKNOWN"
        addPermRow(
            title = "蓝牙耳机模式检测（HFP 支持性）",
            isOn = { hfpOn },
            statusOverride = {
                when (hfp) {
                    "YES" -> "状态：开（已检测：支持）"
                    "NO" -> "状态：开（已检测：不支持）"
                    else -> "状态：关（未检测）"
                }
            },
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
            },
            buttonOverride = {
                // 关 -> 去开启（开始检测）；开 -> 去关闭（清除结果）
                if (hfpOn) "去关闭（清除结果）" else "去开启（开始检测）"
            }
        )

        // 入口（非授权项）
        addPermRow(
            title = "配对（手动配对/扫码）入口",
            isOn = { true },
            statusOverride = { "状态：开（入口）" },
            buttonOverride = { "打开说明" },
            onEnable = { toast("请在主界面进入配对/二维码页面") },
            onDisable = { }
        )

        // 通用：应用详情（方便你在系统里统一关权限）
        addPermRow(
            title = "应用系统设置（统一管理权限/后台/自启动）",
            isOn = { true },
            statusOverride = { "状态：开（入口）" },
            buttonOverride = { "打开系统设置" },
            onEnable = { openAppDetailsSettings() },
            onDisable = { openAppDetailsSettings() }
        )
    }

    private fun addRuntimePermRow(title: String, perm: String) {
        addPermRow(
            title = title,
            isOn = { hasPerm(perm) },
            onEnable = {
                if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(perm), 9001)
            },
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
            statusOverride = {
                if (!hasSim) return@addPermRow "状态：关（无卡机不需要）"
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@addPermRow "状态：关（系统不支持）"
                val rm = getSystemService(RoleManager::class.java)
                val held = rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
                if (held) "状态：开（已开启）" else "状态：关（未开启）"
            },
            onEnable = {
                if (!hasSim) { toast("无卡机不需要此项"); return@addPermRow }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { toast("系统版本不支持"); return@addPermRow }
                val rm = getSystemService(RoleManager::class.java) ?: return@addPermRow
                if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) { toast("系统不提供该角色"); return@addPermRow }
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 9101)
            },
            onDisable = {
                // 不能由 App 主动“取消角色”，只能引导去系统默认应用里切换
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        )
    }

    private fun addPermRow(
        title: String,
        isOn: () -> Boolean,
        onEnable: () -> Unit,
        onDisable: () -> Unit,
        statusOverride: (() -> String)? = null,
        buttonOverride: (() -> String)? = null
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
            text = statusOverride?.invoke() ?: run {
                val on = isOn()
                if (on) "状态：开（已授权/已开启）" else "状态：关（未授权/未开启）"
            }
        }

        val btn = Button(this).apply {
            text = buttonOverride?.invoke() ?: run {
                val on = isOn()
                if (on) "去关闭（系统）" else "去开启"
            }
            setOnClickListener {
                if (isOn()) onDisable() else onEnable()
            }
        }

        row.addView(tv)
        row.addView(st)
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
