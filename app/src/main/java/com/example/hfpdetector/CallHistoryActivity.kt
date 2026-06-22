package com.example.hfpdetector

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import com.example.hfpdetector.db.AppDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class CallHistoryActivity : Activity() {

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "通话记录（LanCall）"
            textSize = 18f
            setPadding(30, 30, 30, 10)
        }

        val btnRefresh = Button(this).apply { text = "刷新" }
        val btnClear = Button(this).apply { text = "清空" }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 10, 30, 10)
            addView(btnRefresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(bar)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        btnRefresh.setOnClickListener { load() }
        btnClear.setOnClickListener {
            thread {
                AppDb.get(this).callLogDao().clear()
                runOnUiThread { load() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        thread {
            val items = runCatching { AppDb.get(this).callLogDao().listAll() }.getOrDefault(emptyList())
            val lines = items.map {
                val t = fmt.format(Date(it.ts))
                "$t  ${it.direction}  ${it.number}  ${it.state}  (${it.peerIp})"
            }
            runOnUiThread {
                adapter.clear()
                adapter.addAll(lines)
                adapter.notifyDataSetChanged()
            }
        }
    }
}
