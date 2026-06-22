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

        // 确保“来电记录”存在（INVITE 收到时 CoreService 也会写，这里再 upsert 一次也没问题）
        HistoryStore.upsertCall(
            context = this,
            callId = invite.callId,
            direction = "IN",
            number = number,
            peerIp = invite.peerIp,
            isTest = false,
            state = "RINGING",
            ts = System.currentTimeMillis()
        )

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

        val btnDecline = circleButton("#D32F2F", android.R.drawable.ic_menu_close_clear_cancel)
        val btnAccept = circleButton("#2E7D32", android.R.drawable.sym_action_call)

        bottom.addView(btnDecline)
        bottom.addView(btnAccept)

        root.addView(tvTop)
        root.addView(center)
        root.addView(bottom)

        setContentView(root)

        btnDecline.setOnClickListener {
            if (inCall) hangup() else decline()
        }
        btnAccept.setOnClickListener { accept() }
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

        AudioCallService.start(
            context = this,
            peerIp = invite.peerIp,
            peerAudioPort = invite.peerAudioPort,
            myAudioPort = myAudioPort
        )

        inCall = true
        // 不 finish，避免“看起来退桌面”
    }

    private fun decline() {
        val invite = CallState.incoming ?: run { finish(); return }
        HistoryStore.updateCallState(this, invite.callId, "DECLINED")

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

    private fun hangup() {
        val invite = CallState.incoming
        if (invite != null) {
            HistoryStore.updateCallState(this, invite.callId, "ENDED")
            sendControl(
                ip = invite.peerIp,
                port = invite.peerControlPort,
                json = JSONObject()
                    .put("type", "HANGUP")
                    .put("callId", invite.callId)
                    .toString()
            )
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
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                marginEnd = dp(16)
            }
            scaleType = ImageView.ScaleType.CENTER
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
