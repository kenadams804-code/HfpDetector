package com.example.hfpdetector

import android.content.Context

object ConnectionState {
    fun isConnected(context: Context): Boolean {
        // 规则：最近 10 秒看到对端，或已配置手动配对 IP
        val now = System.currentTimeMillis()
        val seen = Prefs.getPeerSeenTs(context)
        val recent = (System.currentTimeMillis() - Prefs.getPeerSeenTs(context)) <= 10_000
        return recent
        val manual = Prefs.isManualPairEnabled(context) && Prefs.getPeerIp(context).isNotBlank()
        return recent || manual
    }
}
