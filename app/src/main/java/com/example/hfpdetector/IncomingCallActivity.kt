package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

class IncomingCallActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏弹出/亮屏（新旧系统兼容）
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

        // 尝试请求解锁（不一定每台机型都允许，但可尝试）
        if (Build.VERSION.SDK_INT >= 26) {
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        }

        val invite = CallState.incoming
        if (invite == null) {
            finish()
            return
        }

        val number = invite.number.ifBlank { "未知号码" }

        // ===== UI =====
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val tvTop = TextView(this).apply {
            text = "来电"
            textSize = 28f
            setTextColor(Color.parseColor("#111111"))
            typeface = Typeface.DEFAULT_BOLD
        }

        val tvSub = TextView(this).apply {
            text = "局域网接听（无卡手机）"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(8), 0, 0)
        }

        val centerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val tvNumber = TextView(this).apply {
            text = number
            textSize = 40f
            setTextColor(Color.parseColor("#111111"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val tvHint = TextView(this).apply {
            text = "点“接听”后开始两机免提对讲"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }

        centerBox.addView(tvNumber)
        centerBox.addView(tvHint)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnDecline = bigButton(
            text = "拒绝",
            bgColor = "#D32F2F"
        )

        val space = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), 1)
        }

        val btnAccept = bigButton(
            text = "接听",
            bgColor = "#2E7D32"
        )

        bottom.addView(btnDecline, LinearLayout.LayoutParams(0, dp(72), 1f))
        bottom.addView(space)
        bottom.addView(btnAccept, LinearLayout.LayoutParams(0, dp(72), 1f))

        root.addView(tvTop)
        root.addView(tvSub)
        root.addView(centerBox)
        root.addView(bottom)

        setContentView(root)

        // ===== Click =====
        btnAccept.setOnClickListener { accept() }
        btnDecline.setOnClickListener { decline() }

        // 自动超时（例如 35 秒没操作就停止响铃并关闭界面）
        mainHandler.postDelayed({
            if (!finished) {
                CoreService.stopRingingNow(this)
                CallState.incoming = null
                finish()
            }
        }, 35_000)
    }

    private fun accept() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5001)
            return
        }

        val invite = CallState.incoming ?: run { finish(); return }

        // 我方（接听端）选择一个音频端口
        val myAudioPort = Random.nextInt(46000, 46999)

        // 1) 回 ACCEPT 给发起方
        sendControl(
            ip = invite.peerIp,
            port = invite.peerControlPort,
            json = JSONObject()
                .put("type", "ACCEPT")
                .put("callId", invite.callId)
