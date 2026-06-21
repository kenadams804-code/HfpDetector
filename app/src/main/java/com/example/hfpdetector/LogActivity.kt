package com.example.hfpdetector

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import kotlin.concurrent.thread

class LogActivity : Activity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tvSummary = TextView(this).apply {
            textSize = 13f
            setPadding(30, 30, 30, 20)
        }

        val btnRefresh = Button(this).apply { text = "刷新" }
        val btnClear = Button(this).apply { text = "清空日志" }
        val btnPing = Button(this).apply { text = "连通性测试（有卡机点：在线/不在线）" }
        val btnExport = Button(this).apply { text = "导出诊断包（zip）到下载目录并分享" }

        tvLog = TextView(this).apply {
            textSize = 12f
            setPadding(30, 30, 30, 30)
        }

        val scroll = ScrollView(this).apply { addView(tvLog) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvSummary)
            addView(btnRefresh)
            addView(btnClear)
            addView(btnPing)
            addView(btnExport)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        setContentView(root)

        btnRefresh.setOnClickListener { refreshAll() }

        btnClear.setOnClickListener {
            AppLog.clear(this)
            refreshAll()
        }

        btnPing.setOnClickListener { pingTest() }

        btnExport.setOnClickListener {
            val uri = DebugExport.exportToDownloads(this)
            if (uri == null) {
                toast("导出失败")
                return@setOnClickListener
            }
            toast("已导出到：下载/LanCall/")

            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享诊断包"))
        }

        refreshAll()
    }

    private fun refreshAll() {
        tvSummary.text = buildSummary()
        val log = AppLog.get(this)
        tvLog.text = if (log.isBlank()) "暂无日志。\n建议：两台手机都开启常驻服务后再测试来电/配对。" else log
    }

    private fun buildSummary(): String {
        val tm = getSystemService(TelephonyManager::class.java)
        val hasSim = tm?.simState == TelephonyManager.SIM_STATE_READY

        val now = System.currentTimeMillis()
        val seenTs = Prefs.getPeerSeenTs(this)
        val seenAgo = if (seenTs <= 0) "从未" else "${(now - seenTs) / 1000}s 前"
        val connected = ConnectionState.isConnected(this)

        return buildString {
            append("本机：${if (hasSim) "有卡手机" else "无卡手机"}\n")
            append("常驻服务开关：${if (Prefs.isServiceEnabled(this@LogActivity)) "开" else "关"}\n")
            append("模式：${Prefs.getMode(this@LogActivity)}\n")
            append("手动配对：${if (Prefs.isManualPairEnabled(this@LogActivity)) "开" else "关"}\n")
            append("保存的对端IP：${Prefs.getPeerIp(this@LogActivity).ifBlank { "(空)" }}\n")
            append("最近一次对端响应：$seenAgo  (${Prefs.getPeerSeenIp(this@LogActivity).ifBlank { "未知IP" }})\n")
            append("连接状态：${if (connected) "已连接" else "未连接"}\n")
            append("HFP检测结果：${Prefs.getHfpSupport(this@LogActivity)}\n")
        }
    }

    /** 有卡机点一下：发 PING_TEST，收到 PONG_TEST 就算在线 */
    private fun pingTest() {
        val ip = pickPeerIp()
        if (ip.isBlank()) {
            toast("没有对端IP：请先扫码/填写IP 或确保对端在线")
            return
        }

        toast("测试中…")
        thread {
            val ok = doPingTest(ip, AppConfig.CONTROL_PORT)
            runOnUiThread {
                toast(if (ok) "对端在线" else "对端不在线/未响应")
                refreshAll()
            }
        }
    }

    private fun pickPeerIp(): String {
        // 优先用“手动配对保存IP”，否则用“最近一次看到的IP”
        val saved = Prefs.getPeerIp(this)
        if (saved.isNotBlank()) return saved
        return Prefs.getPeerSeenIp(this)
    }

    private fun doPingTest(ip: String, port: Int): Boolean {
        val nonce = UUID.randomUUID().toString()
        val req = JSONObject().put("type", "PING_TEST").put("nonce", nonce).toString()
        val data = req.toByteArray(Charsets.UTF_8)

        DatagramSocket().use { s ->
            s.soTimeout = 1200
            val p = DatagramPacket(data, data.size, InetAddress.getByName(ip), port)
            s.send(p)

            val buf = ByteArray(1024)
            val resp = DatagramPacket(buf, buf.size)

            return try {
                s.receive(resp)
                val txt = String(resp.data, 0, resp.length, Charsets.UTF_8)
                val obj = JSONObject(txt)
                obj.optString("type") == "PONG_TEST" && obj.optString("nonce") == nonce
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }
}
