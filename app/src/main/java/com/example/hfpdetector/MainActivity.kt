package com.example.hfpdetector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.*

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
    private lateinit var btnSms: Button
    private lateinit var btnCalls: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, 1200)
        }
    }

    private var hasSim: Boolean = false

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

        // 模式下拉框（两项永远显示）
        spinner = Spinner(this)
        modeAdapter = ModeAdapter(this, listOf("局域网模式（LAN）", "蓝牙耳机模式（BT）"))
        spinner.adapter = modeAdapter

        val currentMode = Prefs.getMode(this)
        lastGoodSelection = if (currentMode == "BT") 1 else 0
        spinner.setSelection(lastGoodSelection)

        // ===== 常驻服务：设置风格一行（图标变色 + 右侧Switch）=====
        val (rowService, serviceSwitch, serviceIcon) = makeSettingSwitchRow(
            iconRes = android.R.drawable.ic_popup_sync, // 系统自带图标
            title = "常驻服务",
            subtitle = "开=后台工作（来电/连接可用）"
        )
        swService = serviceSwitch
        ivService = serviceIcon
        swService.isChecked = Prefs.isServiceEnabled(this)
        updateServiceIconColor(swService.isChecked)

        // 有卡机静音开关（普通 Switch 即可）
        swSilence = Switch(this).apply {
            text = "（仅有卡机）来电尽量静音（主要无卡机响）"
            isChecked = Prefs.isSilencePstn(this@MainActivity)
            visibility = if (hasSim) View.VISIBLE else View.GONE
        }

        btnSettings = Button(this).apply { text = "设置（授权/检测/系统跳转）" }
        btnPair = Button(this).apply { text = "配对（有卡机：填IP/扫码）" }
        btnShowQr = Button(this).apply { text = "显示二维码（无卡机）" }
        btnLog = Button(this).apply { text = "系统日志（连通性测试/导出诊断包）" }

        btnSms = Button(this).apply { text = "短信箱（App内）" }
        btnCalls = Button(this).apply { text = "通话记录（App内）" }

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

            addView(btnSettings)
            addView(btnPair)
            addView(btnShowQr)
            addView(btnLog)

            addView(btnSms)
            addView(btnCalls)
        }
        setContentView(root)

        swService.setOnCheckedChangeListener { _, checked ->
            Prefs.setServiceEnabled(this, checked)
            updateServiceIconColor(checked)
            if (checked) CoreService.start(this) else CoreService.stop(this)
            refresh()
        }

        swSilence.setOnCheckedChangeListener { _, checked ->
            Prefs.setSilencePstn(this, checked)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val allow = modeAdapter.isEnabled(position)
                if (!allow) {
                    Toast.makeText(this@MainActivity, "当前不可用：请先配对/或蓝牙不支持", Toast.LENGTH_SHORT).show()
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

        btnSms.setOnClickListener {
            runCatching { startActivity(Intent(this, Class.forName("com.example.hfpdetector.SmsListActivity"))) }
                .onFailure { Toast.makeText(this, "短信箱页面尚未加入/类名不匹配", Toast.LENGTH_SHORT).show() }
        }
        btnCalls.setOnClickListener {
            runCatching { startActivity(Intent(this, Class.forName("com.example.hfpdetector.CallLogActivity"))) }
                .onFailure { Toast.makeText(this, "通话记录页面尚未加入/类名不匹配", Toast.LENGTH_SHORT).show() }
        }

        // 默认根据开关决定是否启动
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

    private fun refresh() {
        val serviceOn = Prefs.isServiceEnabled(this)
        val connected = ConnectionState.isConnected(this)
        val hfpYes = Prefs.getHfpSupport(this) == "YES"

        modeAdapter.enableLan = serviceOn && connected
        modeAdapter.enableBt = serviceOn && connected && hfpYes
        modeAdapter.notifyDataSetChanged()

        val localIp = runCatching { NetUtils.getLocalWifiIp(this) }.getOrDefault("")
        val seenAgoSec = run {
            val ts = Prefs.getPeerSeenTs(this)
            if (ts <= 0) -1 else ((System.currentTimeMillis() - ts) / 1000).toInt()
        }

        tip.text = buildString {
            append("本机：${if (hasSim) "有卡手机" else "无卡手机"}\n")
            append("常驻服务：${if (serviceOn) "开" else "关"}\n")
            append("连接状态：${if (connected) "已连接" else "未连接"}")
            if (seenAgoSec >= 0) append("（$seenAgoSec 秒前）")
            append("\n")
            append("对端IP(保存)：${Prefs.getPeerIp(this@MainActivity).ifBlank { "(空)" }}\n")
            append("对端IP(最近看到)：${Prefs.getPeerSeenIp(this@MainActivity).ifBlank { "(空)" }}\n")
            append("本机Wi‑Fi IP：${if (localIp.isBlank()) "(未获取到)" else localIp}\n")
            append("蓝牙耳机模式支持：${Prefs.getHfpSupport(this@MainActivity)}\n")
            append("提示：日志页里可一键“连通性测试/导出诊断包”。")
        }
    }

    private fun updateServiceIconColor(on: Boolean) {
        // 开=绿色，关=灰色（你也可以改成更亮的绿色）
        ivService.setColorFilter(Color.parseColor(if (on) "#2E7D32" else "#9E9E9E"))
    }

    /**
     * 生成一条“设置风格”的行：左图标 + 两行文字 + 右侧Switch
     * 返回 Triple(row, switch, icon)
     */
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

        // 点击整行也切换开关（更像系统设置）
        row.setOnClickListener { sw.isChecked = !sw.isChecked }

        row.addView(icon)
        row.addView(textBox)
        row.addView(sw)

        return Triple(row, sw, icon)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
