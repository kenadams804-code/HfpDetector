package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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

    private lateinit var btnSettings: Button
    private lateinit var btnPair: Button
    private lateinit var btnShowQr: Button
    private lateinit var btnLog: Button
    private lateinit var btnTestInvite: Button
    private lateinit var btnQuickSetup: Button

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
            subtitle = "开=后台待机（来电/消息到达就触发）"
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

        btnTestInvite = Button(this).apply { text = "发送测试来电（INVITE）" }
        btnTestInvite.visibility = if (hasSim) View.VISIBLE else View.GONE

        btnPair.visibility = if (hasSim) View.VISIBLE else View.GONE
        btnShowQr.visibility = if (hasSim) View.GONE else View.VISIBLE

        val root = LinearLayout(this).apply {
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
            addView(btnTestInvite)
        }
        setContentView(root)

        btnQuickSetup.setOnClickListener { runQuickSetup(force = true) }

        swService.setOnCheckedChangeListener { _, checked ->
            Prefs.setServiceEnabled(this, checked)
            updateServiceIconColor(checked)

            if (checked) {
                runQuickSetup(force = false)
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

        btnTestInvite.setOnClickListener {
            CoreService.sendTestInvite(this)
            Toast.makeText(this, "已发送测试 INVITE（对方在线就会弹窗）", Toast.LENGTH_SHORT).show()
        }

        // 启动时自动准备一次（缺权限才会弹；force=false 不会每次都烦你）
        runQuickSetup(force = false)

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

    private fun runQuickSetup(force: Boolean) {
        requestAllRuntimePermissionsIfNeeded(force)
        requestIgnoreBatteryOptimizationsIfNeeded(force)
        openNotificationSettingsIfDisabled(force)
    }

    private fun hasPerm(p: String): Boolean {
        // ContextCompat 在低版本也安全
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllRuntimePermissionsIfNeeded(force: Boolean) {
        val askedKey = "asked_runtime_perms"
        if (!force && setupSp.getBoolean(askedKey, false)) {
            // 已经问过一次就不主动再问；除非你点“一键授权/准备（强制）”
        }

        // 运行时权限从 Android 6(API 23) 开始才存在
        if (Build.VERSION.SDK_INT < 23) return

        val need = mutableListOf<String>()

        // Android 13+ 通知运行时权限
        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                need += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        // 麦克风（对讲必须）
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            need += Manifest.permission.RECORD_AUDIO
        }

        // 相机（扫码配对可能会用到）
        if (!hasPerm(Manifest.permission.CAMERA)) {
            need += Manifest.permission.CAMERA
        }

        // Android 12+ 蓝牙连接权限（BT模式入口用）
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                need += Manifest.permission.BLUETOOTH_CONNECT
            }
        }

        if (need.isNotEmpty()) {
            setupSp.edit().putBoolean(askedKey, true).apply()
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS_ALL)
        }
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded(force: Boolean) {
        // 忽略电池优化的申请从 Android 6(API 23) 才有意义
        if (Build.VERSION.SDK_INT < 23) return

        val askedKey = "asked_ignore_batt"
        if (!force && setupSp.getBoolean(askedKey, false)) return

        val pm = getSystemService(PowerManager::class.java) ?: return
        val ignoring = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        if (ignoring) return

        setupSp.edit().putBoolean(askedKey, true).apply()

        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(i)
        } catch (_: Throwable) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openNotificationSettingsIfDisabled(force: Boolean) {
        val askedKey = "asked_notif_settings"
        if (!force && setupSp.getBoolean(askedKey, false)) return

        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (enabled) return

        setupSp.edit().putBoolean(askedKey, true).apply()

        Toast.makeText(this, "请允许 LanCall 通知，否则后台/来电弹窗不稳定", Toast.LENGTH_LONG).show()
        try {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(i)
        } catch (_: Throwable) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS_ALL) refresh()
    }

    private fun refresh() {
        val serviceOn = Prefs.isServiceEnabled(this)
        val connected = ConnectionState.isConnected(this)
        val hfpYes = Prefs.getHfpSupport(this) == "YES"

        // 模式A：不再用 connected 禁用（避免按钮变灰/体验不可预知）
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
        val micGranted = if (Build.VERSION.SDK_INT < 23) true else hasPerm(Manifest.permission.RECORD_AUDIO)

        tip.text = buildString {
            append("本机：${if (hasSim) "有卡手机" else "无卡手机"}\n")
            append("常驻服务：${if (serviceOn) "开" else "关"}\n")
            append("连接状态：${if (connected) "已连接" else "未连接"}")
            if (seenAgoSec >= 0) append("（$seenAgoSec 秒前）")
            append("\n")
            append("通知总开关：${if (notifEnabled) "允许" else "未允许"}\n")
            append("麦克风权限：${if (micGranted) "已授权" else "未授权"}\n")
            append("对端IP(保存)：${Prefs.getPeerIp(this@MainActivity).ifBlank { "(空)" }}\n")
            append("对端IP(最近看到)：${Prefs.getPeerSeenIp(this@MainActivity).ifBlank { "(空)" }}\n")
            append("本机Wi‑Fi IP：${if (localIp.isBlank()) "(未获取到)" else localIp}\n")
            append("蓝牙耳机模式支持：${Prefs.getHfpSupport(this@MainActivity)}\n")
        }
    }

    private fun updateServiceIconColor(on: Boolean) {
        ivService.setColorFilter(Color.parseColor(if (on) "#2E7D32" else "#9E9E9E"))
    }

    private fun makeSettingSwitchRow(
        iconRes: Int,
        title: String,
        subtitle: String
    ): Triple<View, Switch, ImageView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 22, 40, 22)
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                rightMargin = dp(14)
            }
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
