package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

class IncomingCallActivity : Activity() {

    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        tv = TextView(this).apply {
            textSize = 20f
            setPadding(40, 60, 40, 40)
        }
        val btnAccept = Button(this).apply { text = "接听（局域网对讲）" }
        val btnDecline = Button(this).apply { text = "拒绝" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(btnAccept)
            addView(btnDecline)
        }
        setContentView(root)

        val invite = CallState.incoming
        tv.text = if (invite != null) "来电号码：${invite.number}" else "无来电信息"

        btnAccept.setOnClickListener { accept() }
        btnDecline.setOnClickListener { decline() }
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
                .put("audioPort", myAudioPort) // 我方音频端口（对方要发给我）
                .toString()
        )

        // 2) 立刻停铃（只停铃，不停 CoreService）
        CoreService.stopRingingNow(this)

        // 3) 启动音频服务（我方开始收发音频）
        AudioCallService.start(
            context = this,
            peerIp = invite.peerIp,
            peerAudioPort = invite.peerAudioPort, // 对方（发起方）的音频端口
            myAudioPort = myAudioPort
        )

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

    private fun sendControl(ip: String, port: Int, json: String) {
        try {
            val data = json.toByteArray(Charsets.UTF_8)
            DatagramSocket().use { s ->
                val p = DatagramPacket(data, data.size, InetAddress.getByName(ip), port)
                s.send(p)
            }
        } catch (_: Throwable) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5001) accept()
    }
}
