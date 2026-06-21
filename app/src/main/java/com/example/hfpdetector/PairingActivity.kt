package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator

class PairingActivity : Activity() {

    private lateinit var etIp: EditText
    private lateinit var swEnable: Switch
    private lateinit var tvState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tvState = TextView(this).apply {
            textSize = 14f
            setPadding(30, 30, 30, 10)
        }

        swEnable = Switch(this).apply {
            text = "启用手动配对（更稳定，不依赖广播）"
            isChecked = Prefs.isManualPairEnabled(this@PairingActivity)
        }

        etIp = EditText(this).apply {
            hint = "输入无卡手机 IP，例如 192.168.1.88"
            setPadding(30, 20, 30, 20)
            setText(Prefs.getPeerIp(this@PairingActivity))
        }

        val btnSave = Button(this).apply { text = "保存 IP" }
        val btnScan = Button(this).apply { text = "扫码配对（推荐）" }
        val btnClear = Button(this).apply { text = "清除配对" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvState)
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
            Prefs.setManualPairEnabled(this, true)
            refresh()
            toast("已保存")
        }

        btnClear.setOnClickListener {
            Prefs.clearPeer(this)
            Prefs.setManualPairEnabled(this, false)
            etIp.setText("")
            refresh()
            toast("已清除")
        }

        btnScan.setOnClickListener {
            // 先要相机权限
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 7001)
                return@setOnClickListener
            }
            startScan()
        }

        refresh()
    }

    private fun startScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("对准无卡手机显示的二维码")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(true)
        integrator.initiateScan()
    }

    private fun refresh() {
        val enabled = Prefs.isManualPairEnabled(this)
        val ip = Prefs.getPeerIp(this)

        tvState.text = buildString {
            append("手动配对：${if (enabled) "已启用" else "未启用"}\n")
            append("接听端 IP：${if (ip.isBlank()) "未设置" else ip}\n")
            append("\n说明：\n无卡手机打开“显示二维码”，有卡手机扫码即可。")
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
                refresh()
                toast("扫码成功：$ip")
            } else {
                toast("二维码内容无法解析")
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7001) startScan()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
