package com.example.hfpdetector

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var tv: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply {
            textSize = 16f
            setPadding(48, 48, 48, 48)
            text = "HfpDetector 检测中..."
        }
        setContentView(tv)

        ensurePermissionThenCheck()
    }

    private fun ensurePermissionThenCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
                return
            }
        }
        runCheck()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            runCheck()
        }
    }

    private fun runCheck() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            tv.text = "❌ 本机不支持蓝牙（BluetoothAdapter=null）"
            return
        }
        if (!adapter.isEnabled) {
            tv.text = "⚠️ 蓝牙未开启，请先打开蓝牙后重试"
            return
        }

        // 额外检测：有些 ROM 会直接删除这个类
        val classExists = try {
            Class.forName("android.bluetooth.BluetoothHeadsetClient")
            true
        } catch (_: Throwable) {
            false
        }

        tv.text = "检测中...\n- BluetoothHeadsetClient 类存在：$classExists\n- 正在尝试连接 HEADSET_CLIENT(16) 服务..."

        val ok = try {
            adapter.getProfileProxy(
                this,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HEADSET_CLIENT) {
                            finished = true
                            tv.text =
                                "🎉 恭喜支持 HFP Headset Client（可以走方案A）\nprofile=$profile\nproxy=${proxy.javaClass.name}"
                            adapter.closeProfileProxy(profile, proxy)
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        // ignore
                    }
                },
                BluetoothProfile.HEADSET_CLIENT
            )
        } catch (t: Throwable) {
            tv.text = "❌ 调用 getProfileProxy 失败：${t.javaClass.simpleName}\n${t.message}"
            return
        }

        if (!ok) {
            tv.text = "❌ 厂商阉割/系统不支持 HEADSET_CLIENT（getProfileProxy 返回 false）\n（建议走方案B：局域网 VoIP）"
            return
        }

        // 超时：请求成功但迟迟不回调，也判定不支持/被阉割
        mainHandler.postDelayed({
            if (!finished) {
                tv.text =
                    "❌ 疑似不支持/被阉割：请求 profile proxy 成功但 4 秒内未回调 onServiceConnected\n（建议走方案B：局域网 VoIP）"
            }
        }, 4000)
    }
}
