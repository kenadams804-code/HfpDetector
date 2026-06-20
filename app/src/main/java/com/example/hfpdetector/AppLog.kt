package com.example.hfpdetector

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val SP = "lancall_log_sp"
    private const val K = "log_text"
    private const val MAX_CHARS = 30000

    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun i(context: Context, msg: String) {
        val line = "${sdf.format(Date())}  $msg\n"
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val old = sp.getString(K, "") ?: ""
        var now = old + line
        if (now.length > MAX_CHARS) {
            now = now.takeLast(MAX_CHARS)
        }
        sp.edit().putString(K, now).apply()
    }

    fun get(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return sp.getString(K, "") ?: ""
    }

    fun clear(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        sp.edit().remove(K).apply()
    }
}
