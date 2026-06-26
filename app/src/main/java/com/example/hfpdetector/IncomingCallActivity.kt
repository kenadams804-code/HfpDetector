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
    private var speakerOn = true
    private var accepting = false

    private lateinit var tvTop: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnLeft: ImageButton
    private lateinit var btnMid: ImageButton
    private lateinit var btnRight: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏弹出支持
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val invite = CallState.incoming ?: run { finish(); return }
        val number = invite.number.ifBlank { "未知号码" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            setPadding(dp(22), dp(40), dp(22), dp(30))
        }

        tvTop = TextView(this).apply {
            text = "来电"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
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
            textSize = 42f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        tvHint = TextView(this).apply {
            text = "接听后开始两机免提对讲"
            textSize = 15f
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }

        center.addView(tvNumber)
        center.addView(tvHint)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun createButton(color: String, icon: Int): ImageButton {
            return ImageButton(this).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(color))
                }
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
            }
        }

        btnLeft = createButton("#D32F2F", android.R.drawable.ic_menu_close_clear_cancel)
        btnMid = createButton(if (speakerOn) "#1976D2" else "#424242", android.R.drawable.ic_btn_speak_now)
        btnRight = createButton("#2E7D32", android.R.drawable.sym_action_call)

        val leftCol = createLabelColumn("拒绝", btnLeft)
        val midCol = createLabelColumn(if (speakerOn) "免提开" else "免提关", btnMid)
        val rightCol = createLabelColumn("接听", btnRight)

        bottom.addView(leftCol)
        bottom.addView(midCol)
        bottom.addView(rightCol)

        root.addView(tvTop)
        root.addView(center)
        root.addView(bottom)

        setContentView(root)

        // 按钮事件
        btnLeft.setOnClickListener { if (inCall) hangup() else decline() }
        btnMid.setOnClickListener { toggleSpeaker() }
        btnRight.setOnClickListener { accept() }
    }

    private fun createLabelColumn(text: String, button: ImageButton): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(button)
            addView(TextView(this@IncomingCallActivity).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        btnMid.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (speakerOn) "#1976D2" else "#424242"))
        }
        if (inCall) {
            AudioCallService.setSpeaker(this, speakerOn)
        }
    }

    private fun accept() {
        if (inCall || accepting) return
        accepting = true

        val invite = CallState.incoming ?: run { finish(); return }

        AppLog.i(this, "IncomingCallActivity：accept() clicked speakerOn=$speakerOn")

        if (Build.VERSION.SDK_INT >= 23 && 
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5001)
            accepting = false
            return
        }

        val myAudioPort = Random.nextInt(46000, 46999)

        AudioCallService.start(this, invite.peerIp, invite.peerAudioPort, myAudioPort, speakerOn)

        sendControlX3(
            invite.peerIp, invite.peerControlPort,
            JSONObject().put("type", "ACCEPT")
                .put("callId", invite.callId)
                .put("audioPort", myAudioPort)
                .toString()
        )

        CoreService.stopRingingNow(this)

        inCall = true
        tvTop.text = "通话中"
        tvHint.text = "局域网免提对讲中..."
        btnRight.isEnabled = false
        accepting = false
    }

    private fun decline() {
        val invite = CallState.incoming ?: run { finish(); return }
        sendControlX3(invite.peerIp, invite.peerControlPort,
            JSONObject().put("type", "DECLINE").put("callId", invite.callId).toString())
        CoreService.stopRingingNow(this)
        CallState.incoming = null
        finish()
    }

    private fun hangup() {
        val invite = CallState.incoming ?: run { finish(); return }
        sendControlX3(invite.peerIp, invite.peerControlPort,
            JSONObject().put("type", "HANGUP").put("callId", invite.callId).toString())
        AudioCallService.stop(this)
        CallState.incoming = null
        finish()
    }

    private fun sendControlX3(ip: String, port: Int, json: String) {
        thread {
            val delays = longArrayOf(0, 120, 300)
            for (d in delays) {
                try { Thread.sleep(d) } catch (_: Throwable) {}
                sendControlOnce(ip, port, json)
            }
        }
    }

    private fun sendControlOnce(ip: String, port: Int, json: String) {
        try {
            DatagramSocket().use { s ->
                val data = json.toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
            }
        } catch (_: Throwable) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                accept()
            }
        }
    }
}
