package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
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
    private var accepting = false

    // 免提开关：true=扬声器免提，false=听筒/非免提
    private var speakerOn = true

    // 防误触：500ms 内不允许连续切换
    private var lastSpeakerToggleTs: Long = 0L

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

        // 锁屏弹出/亮屏
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
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

        // 中间免提按钮：颜色由 updateSpeakerUi() 控制
        btnMid = circleButton("#424242", android.R.drawable.ic_btn_speak_now)
        tvMid = label("")
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

        // 初始路由 + UI
        applySpeakerRoute(speakerOn)
        updateSpeakerUi()

        btnLeft.setOnClickListener { if (inCall) hangup() else decline() }

        btnMid.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastSpeakerToggleTs < 500) return@setOnClickListener
            lastSpeakerToggleTs = now

            speakerOn = !speakerOn
            updateSpeakerUi()
            applySpeakerRoute(speakerOn)

            // ✅ 只有在通话中才通知 Service
            if (inCall) {
                runCatching { AudioCallService.setSpeaker(this, speakerOn) }
            }
        }

        btnRight.setOnClickListener { accept() }
    }

    private fun updateSpeakerUi() {
        val bg = if (speakerOn) "#D32F2F" else "#424242"
        btnMid.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(bg))
        }
        tvMid.text = if (speakerOn) "免提：开" else "免提：关"
    }

    private fun label(t: String): TextView =
        TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }

    private fun accept() {
        if (inCall || accepting) return
        accepting = true

        // UI 立刻反馈（避免连点）
        btnRight.isEnabled = false
        btnRight.alpha = 0.35f

        try {
            AppLog.i(this, "IncomingCallActivity：accept() clicked speakerOn=$speakerOn")

            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                tvHint.text = "需要麦克风权限才能接听，正在请求授权…"
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5001)

                // 等权限回调再接听，这里先恢复按钮
                btnRight.isEnabled = true
                btnRight.alpha = 1f
                accepting = false
                return
            }

            val invite = CallState.incoming ?: run {
                accepting = false
                finish()
                return
            }

            val myAudioPort = Random.nextInt(46000, 46999)
            HistoryStore.updateCallState(this, invite.callId, "ANSWERED")

            // ✅ 直接调用 5 参数 start（与你 AudioCallService 完全匹配）
            AudioCallService.start(
                context = this,
                peerIp = invite.peerIp,
                peerAudioPort = invite.peerAudioPort,
                myAudioPort = myAudioPort,
                speakerOnInit = speakerOn
            )

            // ACCEPT 重发 x3
            sendControlX3(
                ip = invite.peerIp,
                port = invite.peerControlPort,
                json = JSONObject()
                    .put("type", "ACCEPT")
                    .put("callId", invite.callId)
                    .put("audioPort", myAudioPort)
                    .toString(),
                tag = "ACCEPT"
            )

            CoreService.stopRingingNow(this)

            inCall = true
            tvTop.text = "通话中"
            tvHint.text = "正在局域网对讲…（建议两机拉开距离/降低音量）"
            tvLeft.text = "挂断"
            tvRight.text = "已接听"
            // btnRight 已禁用

            accepting = false

        } catch (t: Throwable) {
            AppLog.i(this, "IncomingCallActivity：accept exception: ${t.javaClass.simpleName} ${t.message}")

            // 失败恢复 UI
            accepting = false
            btnRight.isEnabled = true
            btnRight.alpha = 1f
            tvHint.text = "接听失败，请重试"
        }
    }

    private fun decline() {
        val invite = CallState.incoming ?: run { finish(); return }
        AppLog.i(this, "IncomingCallActivity：decline callId=${invite.callId}")

        HistoryStore.updateCallState(this, invite.callId, "DECLINED")

        sendControlX3(
            ip = invite.peerIp,
            port = invite.peerControlPort,
            json = JSONObject()
                .put("type", "DECLINE")
                .put("callId", invite.callId)
                .toString(),
            tag = "DECLINE"
        )

        CoreService.stopRingingNow(this)
        CallState.incoming = null
        finish()
    }

    private fun hangup() {
        val invite = CallState.incoming
        if (invite != null) {
            AppLog.i(this, "IncomingCallActivity：hangup callId=${invite.callId}")
            HistoryStore.updateCallState(this, invite.callId, "ENDED")

            sendControlX3(
                ip = invite.peerIp,
                port = invite.peerControlPort,
                json = JSONObject()
                    .put("type", "HANGUP")
                    .put("callId", invite.callId)
                    .toString(),
                tag = "HANGUP"
            )
        }

        runCatching { AudioCallService.stop(this) }
        CallState.incoming = null
        finish()
    }

    @Suppress("DEPRECATION")
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
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AppLog.i(this, "IncomingCallActivity：RECORD_AUDIO granted")
                accept()
            } else {
                AppLog.i(this, "IncomingCallActivity：RECORD_AUDIO denied")
                tvHint.text = "未授予麦克风权限，无法接听"
                accepting = false
                btnRight.isEnabled = true
                btnRight.alpha = 1f
            }
        }
    }

    private fun sendControlX3(ip: String, port: Int, json: String, tag: String) {
        thread(name = "send-$tag") {
            val delays = longArrayOf(0, 120, 300)
            for (d in delays) {
                try { Thread.sleep(d) } catch (_: Throwable) {}
                sendControlOnce(ip, port, json)
            }
            AppLog.i(this, "IncomingCallActivity：已发送 $tag x3 -> $ip:$port")
        }
    }

    private fun sendControlOnce(ip: String, port: Int, json: String) {
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

    private fun circleButton(bgColor: String, iconRes: Int): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bgColor))
            }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
            scaleType = ImageView.ScaleType.CENTER
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
