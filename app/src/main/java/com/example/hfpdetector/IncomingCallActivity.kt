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

    private lateinit var tvTop: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnLeft: ImageButton
    private lateinit var btnMid: ImageButton
    private lateinit var btnRight: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏弹出
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val invite = CallState.incoming ?: run { finish(); return }
        val number = invite.number.ifBlank { "未知号码" }

        // 布局代码保持你原有样式（省略以节省篇幅，你当前布局可保留）
        // ... 这里直接使用你原来的 root、tvTop、btnLeft 等

        btnLeft.setOnClickListener { if (inCall) hangup() else decline() }
        btnMid.setOnClickListener { toggleSpeaker() }
        btnRight.setOnClickListener { accept() }
    }

    private fun accept() {
        if (inCall) return

        val invite = CallState.incoming ?: run { finish(); return }

        AppLog.i(this, "IncomingCallActivity：accept() clicked speakerOn=$speakerOn")

        val myAudioPort = Random.nextInt(46000, 46999)

        // 启动本地音频对讲
        AudioCallService.start(this, invite.peerIp, invite.peerAudioPort, myAudioPort, speakerOn)

        // 发送 ACCEPT 通知有卡机
        sendControlX3(invite.peerIp, invite.peerControlPort,
            JSONObject().put("type", "ACCEPT")
                .put("callId", invite.callId)
                .put("audioPort", myAudioPort)
                .toString(), "ACCEPT")

        CoreService.stopRingingNow(this)

        inCall = true
        // 更新 UI 为通话中状态...
    }

    private fun hangup() {
        val invite = CallState.incoming ?: run { finish(); return }
        sendControlX3(invite.peerIp, invite.peerControlPort,
            JSONObject().put("type", "HANGUP").put("callId", invite.callId).toString(), "HANGUP")

        AudioCallService.stop(this)
        CallState.incoming = null
        finish()
    }

    private fun decline() {
        val invite = CallState.incoming ?: run { finish(); return }
        sendControlX3(invite.peerIp, invite.peerControlPort,
            JSONObject().put("type", "DECLINE").put("callId", invite.callId).toString(), "DECLINE")

        CoreService.stopRingingNow(this)
        CallState.incoming = null
        finish()
    }

    private fun toggleSpeaker() { /* 原有免提切换逻辑 */ }

    private fun sendControlX3(ip: String, port: Int, json: String, tag: String) {
        thread {
            val delays = longArrayOf(0, 150, 350)
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
}
