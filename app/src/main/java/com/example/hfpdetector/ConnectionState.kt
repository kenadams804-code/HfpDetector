package com.example.hfpdetector

import android.content.Context

object ConnectionState {
    fun isConnected(context: Context): Boolean {
        // 规则：最近 10 秒看到对端，或已配置手动配对 IP
        val now = System.currentTimeMillis()
        val seen = Prefs.getPeerSeenTs(context)
        val recent = seen > 0 && (now - seen) <= 10_000

        val manual = Prefs.isManualPairEnabled(context) && Prefs.getPeerIp(context).isNotBlank()
        return recent || manual
    }
}
