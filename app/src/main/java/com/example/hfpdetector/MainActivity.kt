package com.example.hfpdetector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var modeAdapter: ModeAdapter
    private var lastGoodSelection = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tm = getSystemService(TelephonyManager::class.java)
        val hasSim = tm?.simState == TelephonyManager.SIM_STATE_READY

        val title = TextView(this).apply {
            text = "LanCall"
            textSize = 20f
            setPadding(40, 40, 40, 10)
        }

        val tip = TextView(this).apply {
            textSize = 14f
            setPadding(40, 0, 40, 20)
            setTextColor(Color.DKGRAY)
        }

        // 模式下拉框（两项永远显示）
        val spinner = Spinner(this)
        modeAdapter = ModeAdapter(
            this,
            listOf("局域网模式（LAN）", "蓝牙耳机模式（BT）")
        )
        spinner.adapter = modeAdapter

        val currentMode = Prefs.getMode(this)
        lastGoodSelection = if (currentMode == "BT") 1 else 0
        spinner.setSelection(lastGoodSelection)

        // 常驻服务开关 + 图标颜色（简单版：文字+Switch；你要更像系统设置样式可以下一步再美化）
        val swService = Switch(this).apply {
            text = "常驻服务（开=后台工作）"
            isChecked = Prefs.isServiceEnabled(this@MainActivity)
        }

        val swSilence = Switch(this).apply {
            text = "（仅有卡机）来电尽量静音（主要无卡机响）"
            isChecked = Prefs.isSilencePstn(this@MainActivity)
            visibility = if (hasSim) View.VISIBLE else View.GONE
        }

        val btnSettings = Button(this).apply { text = "设置（授权/检测/系统跳转）" }
        val btnSms = Button(this).apply { text = "短信箱（App内）" }
        val btnCalls = Button(this).apply { text = "通话记录（App内）" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(tip)
            addView(TextView(this@MainActivity).apply {
                text = "当前模式："
                setPadding(40, 0, 40, 0)
            })
            addView(spinner)
            addView(swService)
            addView(swSilence)
            addView(btnSettings)
            addView(btnSms)
            addView(btnCalls)
        }
        setContentView(root)

        fun refresh() {
            val serviceOn = Prefs.isServiceEnabled(this)
            val connected = ConnectionState.isConnected(this)
            val hfp = Prefs.getHfpSupport(this) == "YES"

            // 规则：未连接 -> 两个都灰
            // 已连接 -> LAN 可用；BT 需 HFP=YES 才可用
            modeAdapter.enableLan = serviceOn && connected
            modeAdapter.enableBt = serviceOn && connected && hfp
            modeAdapter.notifyDataSetChanged()

            tip.text = buildString {
                append("本机：${if (hasSim) "有卡手机" else "无卡手机"}\n")
                append("连接状态：${if (connected) "已连接/已配对" else "未连接（请先配对）"}\n")
                append("蓝牙耳机模式支持：${Prefs.getHfpSupport(this@MainActivity)}\n")
                append("提示：短信/通话记录像 WhatsApp 一样独立在 App 内。")
            }
        }

        swService.setOnCheckedChangeListener { _, checked ->
            Prefs.setServiceEnabled(this, checked)
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
                    // 不能选：弹提示并回退
                    Toast.makeText(this@MainActivity, "当前不可用：请先配对/或蓝牙不支持", Toast.LENGTH_SHORT).show()
                    spinner.setSelection(lastGoodSelection)
                    return
                }

                lastGoodSelection = position
                Prefs.setMode(this@MainActivity, if (position == 1) "BT" else "LAN")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        btnSms.setOnClickListener {
            // 你若已有 SmsListActivity 就跳过去；没有就先别点
            runCatching { startActivity(Intent(this, Class.forName("com.example.hfpdetector.SmsListActivity"))) }
        }
        btnCalls.setOnClickListener {
            runCatching { startActivity(Intent(this, Class.forName("com.example.hfpdetector.CallLogActivity"))) }
        }

        // 默认根据开关决定是否启动
        if (Prefs.isServiceEnabled(this)) CoreService.start(this)

        refresh()
    }
}
