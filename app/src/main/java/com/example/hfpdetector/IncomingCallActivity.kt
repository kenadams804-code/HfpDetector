package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import kotlin.concurrent.thread
import kotlin.random.Random

class IncomingCallActivity : Activity() {

    private var inCall = false
    private var speakerOn = true

    private lateinit var tvTop: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvLeft: TextView
    private lateinit var tvMid: TextView
    private lateinit var tvRight: TextView

    private lateinit var btnLeft: ImageButton
    private lateinit var btnMid: ImageButton
    private lateinit var btnRight: ImageButton

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

        val invite = CallState.incoming ?: run { finish(); return }
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

        // ===== 底部三按钮一排 =====
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun col(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val leftCol = col()
        val midCol = col()
        val rightCol = col()

        btnLeft = circleButton("#D32F2F", android.R.drawable.ic_menu_close_clear_cancel)
        tvLeft = label("拒绝")
        leftCol.addView(btnLeft); leftCol.addView(tvLeft)

        btnMid = circleButton("#424242", android.R.drawable.ic_btn_speak_now)
        tvMid = label("免提：开")
        midCol.addView(btnMid); midCol.addView(tvMid)

        btnRight = circleButton("#2E7D32", android.R.drawable.sym_action_call)
        tvRight = label("接听")
        rightCol.addView(btnRight); rightCol.addView(tvRight)

        bottom.addView(leftCol)
        bottom.addView(midCol)
        bottom.addView(rightCol)

        root.addView(tvTop)
        root.addView(center)
        root.addView(bottom)

        setContentView(root)

        // 默认免提打开
        applySpeakerRoute(true)

        btnLeft.setOnClickListener {
            if (inCall) hangup() else decline()
        }

        btnMid.setOnClickListener {
            speakerOn = !speakerOn
            tvMid.text = if (speakerOn) "免提：开" else "免提：关"
            applySpeakerRoute(speakerOn)
            // 通话中也通知服务更新路由
            AudioCallService.setSpeaker(this, speakerOn)
        }

        btnRight.setOnClickListener { accept() }
    }

    private fun label(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }
    }

    private fun accept() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5001)
            return
        }

        val invite = CallState.incoming ?: run { finish(); return }

        val myAudioPort = Random.nextInt(46000, 46999)

        HistoryStore.updateCallState(this, invite.callId, "ANSWERED")

        // ✅ 启动本机对讲服务
        AudioCallService.setSpeaker(this, speakerOn)
        AudioCallService.start(
            context = this,
            peerIp = invite.peerIp,
            peerAudioPort = invite.peerAudioPort,
            myAudioPort = myAudioPort
        )

        // ✅ ACCEPT 重发 x3（防 UDP 丢包；有卡机收不到 ACCEPT 就不会启动音频）
        thread(name = "send-accept") {
            val obj = JSONObject()
                .put("type", "ACCEPT")
                .put("callId", invite.callId)
                .put("audioPort", myAudioPort)
                .toString()

            val delays = longArrayOf(0, 120, 300)
            for (d in delays) {
                try { Thread.sleep(d) } catch (_: Throwable) {}
                sendControl(invite.peerIp, invite.peerControlPort, obj)
            }
            AppLog.i(this, "IncomingCallActivity：已发送 ACCEPT x3 -> ${invite.peerIp}:${invite.peerControlPort} myAudioPort=$myAudioPort peerAudioPort=${invite.peerAudioPort}")
        }

        CoreService.stopRingingNow(this)

        // UI 进入“通话中”
        inCall = true
        tvTop.text = "通话中"
        tvHint.text = "正在局域网对讲…（看日志的音频流量）"
        tvLeft.text = "挂断"
        tvRight.text = "已接听"
        btnRight.isEnabled = false
        btnRight.alpha = 0.35f
    }

    private fun decline() {
        val invite = CallState.incoming ?: run { finish(); return }
        HistoryStore.updateCallState(this, invite.callId, "DECLINED")

        val obj = JSONObject()
            .put("type", "DECLINE")
            .put("callId", invite.callId)
            .toString()

        sendControl(invite.peerIp, invite.peerControlPort, obj)

        CoreService.stopRingingNow(this)
        CallState.incoming = null
        finish()
    }

    private fun hangup() {
        val invite = CallState.incoming
        if (invite != null) {
            HistoryStore.updateCallState(this, invite.callId, "ENDED")
            val obj = JSONObject()
                .put("type", "HANGUP")
                .put("callId", invite.callId)
                .toString()
            sendControl(invite.peerIp, invite.peerControlPort, obj)
        }

        try { stopService(Intent(this, AudioCallService::class.java)) } catch (_: Throwable) {}
        CallState.incoming = null
        finish()
    }

    override fun onBackPressed() {
        if (inCall) hangup() else decline()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) accept()
        }
    }

    private fun sendControl(ip: String, port: Int, json: String) {
        try {
            val data = json.toByteArray(Charsets.UTF_8)
            DatagramSocket().use { s ->
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
            }
        } catch (_: Throwable) {}
    }

    private fun applySpeakerRoute(on: Boolean) {
        try {
            val am = getSystemService(AudioManager::class.java) ?: return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = on
            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    if (on) am.setCommunicationDevice(speaker) else am.clearCommunicationDevice()
                }
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
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
