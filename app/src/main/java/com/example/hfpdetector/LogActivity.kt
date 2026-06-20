package com.example.hfpdetector

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LogActivity : Activity() {

    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btnRefresh = Button(this).apply { text = "刷新" }
        val btnClear = Button(this).apply { text = "清空日志" }

        tv = TextView(this).apply {
            textSize = 12f
            setPadding(30, 30, 30, 30)
        }

        val scroll = ScrollView(this).apply {
            addView(tv)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnRefresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }

        setContentView(root)

        btnRefresh.setOnClickListener { refresh() }
        btnClear.setOnClickListener {
            AppLog.clear(this)
            refresh()
        }

        refresh()
    }

    private fun refresh() {
        tv.text = AppLog.get(this).ifBlank { "暂无日志。\n提示：先在两台手机都启动常驻服务，然后用第三台手机拨打有卡机测试。" }
    }
}
