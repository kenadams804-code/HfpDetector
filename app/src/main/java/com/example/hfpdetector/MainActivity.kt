package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var modeAdapter: ModeAdapter
    private var lastGoodSelection = 0

    private lateinit var tip: TextView
    private lateinit var spinner: Spinner

    private lateinit var swService: Switch
    private lateinit var ivService: ImageView
    private lateinit var swSilence: Switch

    private lateinit var btnQuickSetup: Button
    private lateinit var btnSettings: Button
    private lateinit var btnPair: Button
    private lateinit var btnShowQr: Button
    private lateinit var btnLog: Button
    private lateinit var btnCalls: Button
    private lateinit var btnSms: Button
    private lateinit var btnTestInvite: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, 1200)
        }
    }

    private var hasSim: Boolean = false

    private val REQ_PERMS_ALL = 7001
    private val setupSp by lazy { getSharedPreferences("lancall_setup", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tm = getSystemService(TelephonyManager::class.java)
        hasSim = tm?.simState == TelephonyManager.SIM_STATE_READY

        val title = TextView(this).apply {
            text = "LanCall"
            textSize = 20f
            setPadding(40, 40, 40, 10)
        }

        tip = TextView(this).apply {
            textSize = 14f
            setPadding(40, 0, 40, 20)
            setTextColor(Color.DKGRAY)
        }

        spinner = Spinner(this)
        modeAdapter = ModeAdapter(this, listOf("局域网模式（LAN）", "蓝牙耳机模式（BT）"))
        spinner.adapter = modeAdapter

        val currentMode = Prefs.getMode(this)
        lastGoodSelection = if (currentMode == "BT") 1 else 0
        spinner.setSelection(lastGoodSelection)

        val (rowService, serviceSwitch, serviceIcon) = makeSettingSwitchRow(
            iconRes = android.R.drawable.ic_popup_sync,
            title = "常驻服务",
            subtitle = "开=后台待机（来电/短信到达就触发）"
        )
        swService = serviceSwitch
        ivService = serviceIcon
        swService.isChecked = Prefs.isServiceEnabled(this)
        updateServiceIconColor(swService.isChecked)

        swSilence = Switch(this).apply {
            text = "（仅有卡机）来电尽量静音（主要无卡机响）"
            isChecked = Prefs.isSilencePstn(this@MainActivity)
            visibility = if (hasSim) View.VISIBLE else View.GONE
        }

        btnQuickSetup = Button(this).apply { text = "一键授权/准备（推荐）" }
        btnSettings = Button(this).apply { text = "设置（授权/检测/系统跳转）" }
        btnPair = Button(this).apply { text = "配对（有卡机：填IP/扫码）" }
        btnShowQr = Button(this).apply { text = "显示二维码（无卡机）" }
        btnLog = Button(this).apply { text = "系统日志（连通性测试/导出诊断包）" }
        btnCalls = Button(this).apply { text = "通话记录（LanCall）" }
        btnSms = Button(this).apply { text = "短信箱（LanCall）" }

        btnTestInvite = Button(this).apply { text = "发送测试来电（INVITE）" }
        btnTestInvite.visibility = if (hasSim) View.VISIBLE else View.GONE

        btnPair.visibility = if (hasSim) View.VISIBLE else View.GONE
        btnShowQr.visibility = if (hasSim) View.GONE else View.VISIBLE

        // ✅ 内容布局（放到 ScrollView 里）
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(title)
            addView(tip)

            addView(TextView(this@MainActivity).apply {
                text = "当前模式："
                setPadding(40, 0, 40, 0)
            })
            addView(spinner)

            addView(rowService)
            addView(swSilence)

            addView(btnQuickSetup)
            addView(btnSettings)
            addView(btnPair)
            addView(btnShowQr)
            addView(btnLog)
            addView(btnCalls)
            addView(btnSms)
            addView(btnTestInvite)

            addView(Space(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(24)
                )
            })
        }

        // ✅ 关键修复：用 FrameLayout.LayoutParams（ScrollView 是 FrameLayout 子类）
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scroll)

        btnQuickSetup.setOnClickListener { requestAllRuntimePermissions(force = true) }

        swService.setOnCheckedChangeListener { _, checked ->
            Prefs.setServiceEnabled(this, checked)
            updateServiceIconColor(checked)
            if (checked) {
                requestAllRuntimePermissions(force = false)
                CoreService.start(this)
            } else {
                CoreService.stop(this)
            }
            refresh()
        }

        swSilence.setOnCheckedChangeListener { _, checked ->
            Prefs.setSilencePstn(this, checked)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val allow = modeAdapter.isEnabled(position)
                if (!allow) {
                    Toast.makeText(this@MainActivity, "当前不可用：请先开启常驻服务/或蓝牙不支持", Toast.LENGTH_SHORT).show()
                    spinner.setSelection(lastGoodSelection)
                    return
                }
                lastGoodSelection = position
                Prefs.setMode(this@MainActivity, if (position == 1) "BT" else "LAN")
                refresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnPair.setOnClickListener { startActivity(Intent(this, PairingActivity::class.java)) }
        btnShowQr.setOnClickListener { startActivity(Intent(this, ShowQrActivity::class.java)) }
        btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }

        btnCalls.setOnClickListener { startActivity(Intent(this, CallHistoryActivity::class.java)) }
        btnSms.setOnClickListener { startActivity(Intent(this, SmsBoxActivity::class.java)) }

        btnTestInvite.setOnClickListener {
            CoreService.sendTestInvite(this)
            Toast.makeText(this, "已发送测试 INVITE（对方在线就会弹窗）", Toast.LENGTH_SHORT).show()
        }

        requestAllRuntimePermissions(force = false)

        if (Prefs.isServiceEnabled(this)) CoreService.start(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(refreshRunnable)
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestAllRuntimePermissions(force: Boolean) {
        val askedKey = "asked_runtime_perms"
        if (!force && setupSp.getBoolean(askedKey, false)) {
            // 已问过就不重复弹
        }

        val need = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasPerm(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        }

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (!hasPerm(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA

        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) need += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (hasSim) {
    if (!hasPerm(Manifest.permission.RECEIVE_SMS)) need += Manifest.permission.RECEIVE_SMS
    if (!hasPerm(Manifest.permission.ANSWER_PHONE_CALLS)) need += Manifest.permission.ANSWER_PHONE_CALLS
    if (!hasPerm(Manifest.permission.READ_PHONE_STATE)) need += Manifest.permission.READ_PHONE_STATE
        }

        if (need.isNotEmpty()) {
            setupSp.edit().putBoolean(askedKey, true).apply()
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS_ALL)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS_ALL) refresh()
    }

    private fun refresh() {
        val serviceOn = Prefs.isServiceEnabled(this)
        val connected = ConnectionState.isConnected(this)
        val hfpYes = Prefs.getHfpSupport(this) == "YES"

        modeAdapter.enableLan = serviceOn
        modeAdapter.enableBt = serviceOn && hfpYes
        modeAdapter.notifyDataSetChanged()

        btnTestInvite.isEnabled = serviceOn

        val localIp = runCatching { NetUtils.getLocalWifiIp(this) }.getOrDefault("")
        val seenAgoSec = run {
            val ts = Prefs.getPeerSeenTs(this)
            if (ts <= 0) -1 else ((System.currentTimeMillis() - ts) / 1000).toInt()
        }

        val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val micGranted = hasPerm(Manifest.permission.RECORD_AUDIO)
        val smsGranted = if (hasSim) hasPerm(Manifest.permission.RECEIVE_SMS) else true

        tip.text = buildString {
            append("本机：${if (hasSim) "有卡手机" else "无卡手机"}\n")
            append("常驻服务：${if (serviceOn) "开" else "关"}\n")
            append("连接状态：${if (connected) "已连接" else "未连接"}")
            if (seenAgoSec >= 0) append("（$seenAgoSec 秒前）")
            append("\n")
            append("通知总开关：${if (notifEnabled) "允许" else "未允许"}\n")
            append("麦克风权限：${if (micGranted) "已授权" else "未授权"}\n")
            if (hasSim) append("短信权限：${if (smsGranted) "已授权" else "未授权"}\n")
            append("对端IP(保存)：${Prefs.getPeerIp(this@MainActivity).ifBlank { "(空)" }}\n")
            append("对端IP(最近看到)：${Prefs.getPeerSeenIp(this@MainActivity).ifBlank { "(空)" }}\n")
            append("本机Wi‑Fi IP：${if (localIp.isBlank()) "(未获取到)" else localIp}\n")
            append("蓝牙耳机模式支持：${Prefs.getHfpSupport(this@MainActivity)}\n")
        }
    }

    private fun updateServiceIconColor(on: Boolean) {
        ivService.setColorFilter(Color.parseColor(if (on) "#2E7D32" else "#9E9E9E"))
    }

    private fun makeSettingSwitchRow(iconRes: Int, title: String, subtitle: String): Triple<View, Switch, ImageView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 22, 40, 22)
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) }
        }

        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#111111"))
        }

        val tvSub = TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
        }

        textBox.addView(tvTitle)
        textBox.addView(tvSub)

        val sw = Switch(this)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }

        row.addView(icon)
        row.addView(textBox)
        row.addView(sw)

        return Triple(row, sw, icon)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
