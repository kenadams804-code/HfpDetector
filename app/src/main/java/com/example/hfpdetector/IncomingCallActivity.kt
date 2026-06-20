package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
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

        // 尝试请求解锁（有锁屏密码时通常不会直接解锁，但不影响显示来电界面）
        if (Build.VERSION.SDK_INT >= 26) {
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        }

        // 暗色背景 + 微弱 dim（更像来电）
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
            textSize = 42f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val tvHint = TextView(this).apply {
            text = "滑动接听后开始两机免提对讲"
            textSize = 14f
            setTextColor(Color.parseColor("#BDBDBD"))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }

        center.addView(tvNumber)
        center.addView(tvHint)

        // 拒绝按钮（大红）
        val btnDecline = bigButton("拒绝", "#D32F2F")

        // 滑动接听（SeekBar）
        val sliderTitle = TextView(this).apply {
            text = "滑动接听"
            textSize = 14f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, dp(14), 0, dp(8))
            gravity = Gravity.CENTER
        }

        val seek = SeekBar(this).apply {
            max = 100
            progress = 0
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val p = seekBar?.progress ?: 0
                if (p >= 92) {
                    accept()
                } else {
                    seekBar?.progress = 0
                }
            }
        })

        root.addView(tvTop)
        root.addView(tvSub)
        root.addView(center)
        root.addView(btnDecline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
        ))
        root.addView(sliderTitle)
        root.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        btnDecline.setOnClickListener { decline() }

        // 超时自动结束（避免一直响）
        mainHandler.postDelayed({
            if (!finished) {
                CoreService.stopRingingNow(this)
                CallState.incoming = null
                finish()
            }
        }, 40_000)
    }

    private fun accept() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5001)
            return
        }

        val invite = CallState.incoming ?: run { finish(); return }

        val myAudioPort = Random.nextInt(46000, 46999)

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

        finished = true
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

        finished = true
        CoreService.stopRingingNow(this)
        CallState.incoming = null
        finish()
    }

    override fun onBackPressed() {
        // 返回键当作拒绝，更像系统来电行为
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

    private fun bigButton(text: String, bgColor: String): Button {
        return Button(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor(bgColor))
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
