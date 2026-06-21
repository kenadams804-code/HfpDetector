package com.example.hfpdetector

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class ShowQrActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ip = NetUtils.getLocalWifiIp(this)
        val payload = "lancall://pair?ip=$ip&port=${AppConfig.CONTROL_PORT}"

        val tv = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(30, 60, 30, 20)
            text = if (ip.isBlank()) {
                "未获取到 Wi‑Fi IP。\n请先连接 Wi‑Fi/热点后再打开本页。"
            } else {
                "接听端（无卡机）IP：$ip\n请用有卡手机扫码配对"
            }
        }

        val iv = ImageView(this).apply {
            setPadding(30, 20, 30, 30)
            setImageBitmap(if (ip.isBlank()) null else makeQr(payload, 820))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            addView(tv)
            addView(iv)
        }

        setContentView(root)
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
