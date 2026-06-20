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

        // 暗色背景 + dim（更像系统来电）
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.55f }

        val invite = CallState.incoming ?: run {
            finish()
            return
        }
        val number = invite.number.ifBlank { "未知号码" }

        // ===== UI =====
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
