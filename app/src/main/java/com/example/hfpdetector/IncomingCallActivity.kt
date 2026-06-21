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
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

class IncomingCallActivity : Activity() {

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

        // ===== UI 根布局 =====
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

        // ===== 底部：左右两个圆形按钮 =====
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

        val btnDecline = circleButton(
            bgColor = "#D32F2F",
            iconRes = android.R.drawable.ic_menu_close_clear_cancel
        )
        val tvDecline = TextView(this).apply {
            text = "拒绝"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }

        val btnAccept = circleButton(
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

        // 组装
        root.addView(tvTop)
        root.addView(tvSub)
        root.addView(center)
        root.addView(bottom)

        setContentView(root)

        // 点击事件（保证一定响应）
        btnDecline.setOnClickListener { decline() }
        btnAccept.setOnClickListener { accept() }
    }

    private fun accept() {
        // 需要麦克风权限
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

        // 停铃/停震动
        CoreService.stopRingingNow(this)

        // 启动音频对讲
        AudioCallService.start(
            context = this,
            peerIp = invite.peerIp,
            peerAudioPort = invite.peerAudioPort,
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

    override fun onBackPressed() {
        // 返回键按“拒绝”处理，更像系统来电
        decline()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5001) {
            accept()
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

            // 大小/边距（你要的“圆形大按钮”）
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
            scaleType = ImageButton.ScaleType.CENTER
            isClickable = true
            isFocusable = true
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
