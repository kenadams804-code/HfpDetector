package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

class IncomingCallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.55f }

        val invite = CallState.incoming ?: run {
            finish()
            return
        }
        val number = invite.number.ifBlank { "未知号码" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            setPadding(dp(22), dp(30), dp(22), dp(26))
        }

        val tvTop = TextView(this).apply {
            text = "来电"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }

        val tvSub = TextView(this).apply {
            text = "局域网接听（无卡手机）"
            textSize = 14f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, dp(8), 0, 0)
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val tvNumber = TextView(this).apply {
            text = number
            textSize = 44f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val tvHint = TextView(this).apply {
            text = "接听后开始两机免提对讲"
            textSize = 14f
            setTextColor(Color.parseColor("#BDBDBD"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        center.addView(tvNumber)
        center.addView(tvHint)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnDecline = circleButton("#D32F2F", android.R.drawable.ic_menu_close_clear_cancel)
        val tvDecline = TextView(this).apply {
            text = "拒绝"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }

        val btnAccept = circleButton("#2E7D32", android.R.drawable.sym_action_call)
        val tvAccept = TextView(this).apply {
            text = "接听"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }

        leftCol.addView(btnDecline)
        leftCol.addView(tvDecline)
        rightCol.addView(btnAccept)
        rightCol.addView(tvAccept)
        bottom.addView(leftCol)
        bottom.addView(rightCol)

        root.addView(tvTop)
        root.addView(tvSub)
        root.addView(center)
        root.addView(bottom)

        setContentView(root)

        btnDecline.setOnClickListener {
            AppLog.i(this, "IncomingCallActivity：点击拒绝")
            decline()
        }

        btnAccept.setOnClickListener {
            AppLog.i(this, "IncomingCallActivity：点击接听")
            accept()
        }
    }

    private fun accept() {
        // 1) 前台通话服务必须能发通知：通知被禁会导致系统直接杀服务/退桌面
        val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!notifEnabled) {
            AppLog.i(this, "接听失败：系统通知被禁用，无法稳定启动通话前台服务")
            toast("请先开启 LanCall 通知，否则无法接听通话")
            openNotificationSettings()
            return
        }

        // 2) 麦克风权限
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5001)
            return
        }

        val invite = CallState.incoming ?: run { finish(); return }

        val myAudioPort = Random.nextInt(46000, 46999)

        // 回 ACCEPT 给有卡机
        sendControl(
            ip = invite.peerIp,
            port = invite.peerControlPort,
            json = JSONObject()
                .put("type", "ACCEPT")
                .put("callId", invite.callId)
                .put("audioPort", myAudioPort)
                .toString()
        )

        CoreService.stopRingingNow(this)

        // 启动通话服务（前台服务）
        try {
            AudioCallService.start(
                context = this,
                peerIp = invite.peerIp,
                peerAudioPort = invite.peerAudioPort,
                myAudioPort = myAudioPort
            )
        } catch (t: Throwable) {
            AppLog.i(this, "启动 AudioCallService 失败：${t.javaClass.simpleName} ${t.message}")
            toast("启动通话失败：请检查通知权限/后台限制")
            return
        }

        CallState.incoming = null
        finish()
    }

    private fun decline() {
        val invite = CallState.incoming ?: run { finish(); return }

        sendControl(
            ip = invite.peerIp,
            port = invite.peerControlPort,
            json = JSONObject()
                .put("type", "DECLINE")
                .put("callId", invite.callId)
                .toString()
        )

        CoreService.stopRingingNow(this)
        CallState.incoming = null
        finish()
    }

    override fun onBackPressed() {
        decline()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5001) accept()
    }

    private fun sendControl(ip: String, port: Int, json: String) {
        try {
            val data = json.toByteArray(Charsets.UTF_8)
            DatagramSocket().use { s ->
                val p = DatagramPacket(data, data.size, InetAddress.getByName(ip), port)
                s.send(p)
            }
        } catch (_: Throwable) {}
    }

    private fun circleButton(bgColor: String, iconRes: Int): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bgColor))
            }

            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
        }
    }

    private fun openNotificationSettings() {
        // 尽量跳到本 App 通知设置
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        runCatching { startActivity(i) }
            .onFailure {
                // 兜底到应用详情
                val j = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                runCatching { startActivity(j) }
            }
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
