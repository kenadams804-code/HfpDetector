package com.example.hfpdetector

import android.content.Context

object Prefs {
    private const val SP = "lancall_sp"

    private const val K_SILENCE_PSTN = "silence_pstn"
    private const val K_MODE = "mode"                 // "LAN" / "BT"
    private const val K_SERVICE_ENABLED = "svc_on"

    // 手动配对（如果你已做 PairingActivity，就用它的存储；这里保持兼容）
    private const val K_MANUAL_PAIR_ENABLED = "manual_pair_enabled"
    private const val K_PEER_IP = "peer_ip"

    // “是否连接”的判断：最近看到对端的时间
    private const val K_PEER_SEEN_TS = "peer_seen_ts"
    private const val K_PEER_SEEN_IP = "peer_seen_ip"

    // HFP 支持性：UNKNOWN/YES/NO
    private const val K_HFP = "hfp_support"

    fun isSilencePstn(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_SILENCE_PSTN, false)

    fun setSilencePstn(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(K_SILENCE_PSTN, v).apply()
    }

    fun getMode(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_MODE, "LAN") ?: "LAN"

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(K_MODE, mode).apply()
    }

    fun isServiceEnabled(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_SERVICE_ENABLED, true)

    fun setServiceEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(K_SERVICE_ENABLED, v).apply()
    }

    fun isManualPairEnabled(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_MANUAL_PAIR_ENABLED, false)

    fun setManualPairEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(K_MANUAL_PAIR_ENABLED, v).apply()
    }

    fun getPeerIp(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_PEER_IP, "") ?: ""

    fun setPeerIp(context: Context, ip: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(K_PEER_IP, ip.trim()).apply()
    }

    fun markPeerSeen(context: Context, ip: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putLong(K_PEER_SEEN_TS, System.currentTimeMillis())
            .putString(K_PEER_SEEN_IP, ip)
            .apply()
    }

    fun getPeerSeenTs(context: Context): Long =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getLong(K_PEER_SEEN_TS, 0L)

    fun getPeerSeenIp(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_PEER_SEEN_IP, "") ?: ""

    fun getHfpSupport(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_HFP, "UNKNOWN") ?: "UNKNOWN"

    fun setHfpSupport(context: Context, v: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(K_HFP, v).apply()
    }
}
