package com.example.hfpdetector

import android.content.Context

object ConnectionState {

    // 多久内收到对端回应算“已连接”
    private const val CONNECT_VALID_MS = 120_000

    fun isConnected(context: Context): Boolean {
        val seen = Prefs.getPeerSeenTs(context)
        if (seen <= 0L) return false
        return (System.currentTimeMillis() - seen) <= CONNECT_VALID_MS
    }
}
