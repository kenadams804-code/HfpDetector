package com.example.hfpdetector

import android.content.Context

object ConnectionState {

    /**
     * ✅ 关键修改：
     * 以前 10 秒窗口太短，熄屏/省电/丢包时会频繁“离线/在线”跳动
     * 改 60 秒后，连接状态会稳定很多，更像“Wi‑Fi 一直连着”
     */
    private const val CONNECT_VALID_MS = 60_000L

    fun isConnected(context: Context): Boolean {
        val seen = Prefs.getPeerSeenTs(context)
        if (seen <= 0L) return false
        return (System.currentTimeMillis() - seen) <= CONNECT_VALID_MS
    }
}
