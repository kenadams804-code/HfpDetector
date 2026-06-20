package com.example.hfpdetector

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator

class PairingActivity : Activity() {

    private lateinit var tv: TextView
    private lateinit var etIp: EditText
    private lateinit var swEnable: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply {
            textSize = 14f
            setPadding(30, 30, 30, 10)
        }

        swEnable = Switch(this).apply {
            text = "启用手动配对（路由器禁用广播时更稳定）"
            isChecked = Prefs.isManualPairEnabled(this@PairingActivity)
        }

        etIp = EditText(this).apply {
            hint = "输入无卡手机 IP（例如 192.168.1.88）"
            setPadding(30, 20, 30, 20)
            setText(Prefs.getPeerIp(this@PairingActivity))
        }

        val btnSave = Button(this).apply { text = "保存 IP" }
        val btnClear = Button(this).apply { text = "清除配对" }
        val btnScan = Button(this).apply { text = "扫码配对（推荐）" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(swEnable)
            addView(etIp)
            addView(btnSave)
            addView(btnScan)
            addView(btnClear)
        }
        setContentView(root)

        swEnable.setOnCheckedChangeListener { _, checked ->
            Prefs.setManualPairEnabled(this, checked)
            refresh()
        }

        btnSave.setOnClickListener {
            Prefs.setPeerIp(this, etIp.text?.toString() ?: "")
            refresh()
        }

        btnClear.setOnClickListener {
            Prefs.clearPeer(this)
            Prefs.setManualPairEnabled(this, false)
            etIp.setText("")
            refresh()
        }

        btnScan.setOnClickListener {
            val integrator = IntentIntegrator(this)
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            integrator.setPrompt("对准无卡手机显示的二维码")
            integrator.setBeepEnabled(false)
            integrator.setOrientationLocked(true)
            integrator.initiateScan()
        }

        refresh()
    }

    private fun refresh() {
        val enabled = Prefs.isManualPairEnabled(this)
        val ip = Prefs.getPeerIp(this)

        tv.text = buildString {
            append("手动配对状态：")
            append(if (enabled) "已启用" else "未启用（将使用广播自动发现）")
            append("\n当前接听端 IP：")
            append(if (ip.isBlank()) "未设置" else ip)
            append("\n\n提示：")
            append("\n- 无卡手机打开主界面 → “显示配对二维码”")
            append("\n- 有卡手机进入本页扫码即可自动填入 IP")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            val content = result.contents ?: return
            val uri = runCatching { Uri.parse(content) }.getOrNull()
            val ip = uri?.getQueryParameter("ip") ?: ""
            if (ip.isNotBlank()) {
                Prefs.setPeerIp(this, ip)
                Prefs.setManualPairEnabled(this, true)
                etIp.setText(ip)
                AppLog.i(this, "扫码配对成功：peerIp=$ip")
            } else {
                AppLog.i(this, "扫码内容无法解析：$content")
            }
            refresh()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }
}
