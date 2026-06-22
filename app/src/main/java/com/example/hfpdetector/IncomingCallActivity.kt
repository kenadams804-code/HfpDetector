package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

class IncomingCallActivity : Activity() {

    private var inCall = false
    private var accepting = false

    private lateinit var tvTop: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvDecline: TextView
    private lateinit var btnDecline: ImageButton
    private lateinit var btnAccept: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏弹出/亮屏
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

        // 尝试解锁（不一定成功，但不影响显示）
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        }

        // 暗色背景 + 轻微 dim
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

        tvTop = TextView(this).apply {
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

        tvHint = TextView(this).apply {
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

        btnDecline = circleButton(
            bgColor = "#D32F2F",
            iconRes = android.R.drawable.ic_menu_close_clear_cancel
        )
        tvDecline = TextView(this).apply {
            text = "拒绝"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }

        btnAccept = circleButton(
            bgColor = "#2E7D32",
            iconRes = android.R.drawable.sym_action_call
        )
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
            if (inCall) {
                AppLog.i(this, "IncomingCallActivity：点击挂断")
                hangup()
            } else {
                AppLog.i(this, "IncomingCallActivity：点击拒绝")
                decline()
            }
        }

        btnAccept.setOnClickListener {
            AppLog.i(this, "IncomingCallActivity：点击接听")
            accept()
        }
    }

    private fun accept() {
        if (accepting || inCall) return
        accepting = true

        // 麦克风权限
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            accepting = false
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5001)
            return
        }

        val invite = CallState.incoming ?: run {
            accepting = false
            finish()
            return
        }

        // 进入“通话中”UI（不再 finish 退桌面）
        enterInCallUi()

        val myAudioPort = Random.nextInt(46000, 46999)

        // 先停铃/震动
        CoreService.stopRingingNow(this)

        // 启动本机对讲服务
        try {
            AudioCallService.start(
                context = this,
                peerIp = invite.peerIp,
                peerAudioPort = invite.peerAudioPort,
                myAudioPort = myAudioPort
            )
            AppLog.i(this, "IncomingCallActivity：已启动 AudioCallService myAudioPort=$myAudioPort peerAudioPort=${invite.peerAudioPort}")
        } catch (t: Throwable) {
            AppLog.i(this, "IncomingCallActivity：启动 AudioCallService 失败：${t.javaClass.simpleName} ${t.message}")
        }

        // 回 ACCEPT 给有卡机（关键：有卡机收到后也要启动 AudioCallService，下面 CoreService 会加处理）
        sendControl(
            ip = invite.peerIp,
            port = invite.peerControlPort,
            json = JSONObject()
                .put("type", "ACCEPT")
                .put("callId", invite.callId)
                .put("audioPort", myAudioPort)
                .toString()
        )

        // 不清空 CallState、不 finish：保持在界面上（像系统电话一样）
        accepting = false
        inCall = true
    }

    private fun enterInCallUi() {
        tvTop.text = "通话中"
        tvHint.text = "正在局域网对讲…"
        tvDecline.text = "挂断"
        btnAccept.isEnabled = false
        btnAccept.alpha = 0.35f
    }

    private fun hangup() {
        val invite = CallState.incoming
        if (invite != null) {
            sendControl(
                ip = invite.peerIp,
                port = invite.peerControlPort,
                json = JSONObject()
                    .put("type", "HANGUP")
                    .put("callId", invite.callId)
                    .toString()
            )
        }

        // 停掉本机对讲服务（不依赖 AudioCallService 是否提供 stop 方法）
        try {
            stopService(Intent(this, AudioCallService::class.java))
        } catch (_: Throwable) {}

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
        if (inCall) hangup() else decline()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5001) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) accept() else AppLog.i(this, "IncomingCallActivity：麦克风权限被拒绝，无法接听")
        }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
