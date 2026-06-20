package com.example.hfpdetector

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "HfpDetector"
    
    // 16 代表系统隐藏的 BluetoothProfile.HEADSET_CLIENT (免提端/耳机角色)
    private val HEADSET_CLIENT_PROFILE_ID = 16 

    private lateinit var resultTextView: TextView
    private lateinit var detectButton: Button
    private var bluetoothAdapter: BluetoothAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 使用纯代码构建简单的测试界面
        val linearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            padding = 60
        }
        detectButton = Button(this).apply { text = "开始检测 HFP 角色" }
        resultTextView = TextView(this).apply { 
            text = "点击上方按钮开始检测当前手机底层..."
            textSize = 18f
        }
        linearLayout.addView(detectButton)
        linearLayout.addView(resultTextView)
        setContentView(linearLayout)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        detectButton.setOnClickListener {
            checkPermissionsAndDetect()
        }
    }

    private fun checkPermissionsAndDetect() {
        if (bluetoothAdapter == null) {
            updateResult("失败: 该设备不支持蓝牙硬件")
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            updateResult("提示: 请先开启手机系统的蓝牙开关")
            return
        }

        // 针对 Android 12 及以上系统的蓝牙运行时权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                return
            }
        }

        startProfileDetection()
    }

    private fun startProfileDetection() {
        updateResult("正在向系统请求 HFP Client 代理服务...\n(Profile ID: 16)")

        val success = bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == HEADSET_CLIENT_PROFILE_ID) {
                    Log.d(TAG, "成功连接到 HFP Client 代理服务")
                    
                    // 获取对端代理类的实际名称
                    val className = proxy.javaClass.name
                    
                    runOnUiThread {
                        updateResult(
                            "🎉 恭喜！当前手机【支持】伪装成蓝牙耳机。\n\n" +
                            "底层类名: $className\n" +
                            "检测结果: 允许开发方案 A。"
                        )
                    }
                    
                    // 及时关闭代理释放资源
                    bluetoothAdapter?.closeProfileProxy(HEADSET_CLIENT_PROFILE_ID, proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                Log.d(TAG, "HFP Client 服务已断开")
            }
        }, HEADSET_CLIENT_PROFILE_ID) ?: false

        if (!success) {
            updateResult("❌ 检测失败:\n系统直接拒绝了 ID 16 的请求。\n这说明厂商在 ROM 固件中阉割了该功能，不支持伪装成耳机。")
        }
    }

    private fun updateResult(text: String) {
        resultTextView.text = text
        Log.i(TAG, text)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startProfileDetection()
        } else {
            updateResult("失败: 缺少蓝牙连接权限，无法完成检测")
        }
    }
}
