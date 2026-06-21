package com.example.hfpdetector

import android.app.Activity
import android.content.Intent
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
        val btnExport = Button(this).apply { text = "导出诊断包（zip）到下载目录" }

        tv = TextView(this).apply {
            textSize = 12f
            setPadding(30, 30, 30, 30)
        }

        val scroll = ScrollView(this).apply { addView(tv) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btnRefresh)
            addView(btnClear)
            addView(btnExport)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        setContentView(root)

        btnRefresh.setOnClickListener { refresh() }

        btnClear.setOnClickListener {
            AppLog.clear(this)
            refresh()
        }

        btnExport.setOnClickListener {
            val uri = DebugExport.exportToDownloads(this)
            if (uri == null) {
                toast("导出失败")
                return@setOnClickListener
            }
            toast("已导出到“下载/LanCall/”")

            // 直接弹出分享（可发给你自己/发给我定位问题）
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享诊断包"))
        }

        refresh()
    }

    private fun refresh() {
        val log = AppLog.get(this)
        tv.text = if (log.isBlank()) {
            "暂无日志。\n建议：两台手机都开启常驻服务后再测试来电/短信。"
        } else log
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }
}
