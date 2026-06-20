package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply {
            textSize = 16f
            setPadding(40, 40, 40, 40)
        }

        val btnPerm = Button(this).apply { text = "申请通知/麦克风权限" }
        val btnStart = Button(this).apply { text = "启动常驻服务（两台手机都点一次）" }
        val btnRole = Button(this).apply { text = "（仅有卡机）设置为来电筛选应用（获取号码）" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(btnPerm)
            addView(btnStart)
            addView(btnRole)
        }
        setContentView(root)

        btnPerm.setOnClickListener { requestRuntimePermissions() }
        btnStart.setOnClickListener { CoreService.start(this) }
        btnRole.setOnClickListener { requestCallScreeningRole() }

        // 自动启动常驻服务（你希望“立即触发”，所以默认启动）
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

        tv.text = buildString {
            append("LanCall 状态\n")
            append("- 本机是否有 SIM：$hasSimReady\n")
            append("- 是否已持有来电筛选角色：$roleHeld\n")
            append("\n使用说明（必须看）：\n")
            append("1) 两台手机都安装同一个 APK\n")
            append("2) 两台手机连同一个 Wi-Fi\n")
            append("3) 两台手机都打开 App，点一次“启动常驻服务”\n")
            append("4) 仅有卡手机点“设置为来电筛选应用”并在系统弹窗里允许\n")
            append("5) 外部来电进入有卡机时，无卡机将弹出来电界面显示号码\n")
            append("6) 无卡机点接听后，开始两机局域网免提对讲\n")
            append("\n注意：这是两机内网通话，不会把外部来电对方接到无卡机。")
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            tv.append("\n\n你的系统版本太低，不支持 RoleManager。")
            return
        }
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            tv.append("\n\n系统不提供 ROLE_CALL_SCREENING。")
            return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            tv.append("\n\n已是来电筛选应用，无需重复设置。")
            return
        }
        try {
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 3001)
        } catch (t: Throwable) {
            tv.append("\n\n拉起系统授权界面失败：${t.message}")
        }
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
