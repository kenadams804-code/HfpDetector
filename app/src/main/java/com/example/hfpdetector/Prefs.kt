package com.example.hfpdetector

import android.content.Context

object Prefs {
    private const val SP = "lancall_sp"

    // 来电静音开关（CallScreeningService 用）
    private const val K_SILENCE_PSTN = "silence_pstn"

    // 模式选择：LAN / BT
    private const val K_MODE = "mode"

    // 常驻服务开关
    private const val K_SERVICE_ENABLED = "svc_on"

    // 手动配对（输入IP/扫码）
    private const val K_MANUAL_PAIR_ENABLED = "manual_pair_enabled"
    private const val K_PEER_IP = "peer_ip"

    // 最近看到对端的时间/IP（用于“是否连接”的判断、灰掉模式）
    private const val K_PEER_SEEN_TS = "peer_seen_ts"
    private const val K_PEER_SEEN_IP = "peer_seen_ip"

    // HFP 支持性：UNKNOWN / YES / NO
    private const val K_HFP = "hfp_support"

    // 开机自启开关（BootReceiver 用）
    private const val K_AUTO_START_BOOT = "auto_start_boot"

    // 最近一次来电（你之前界面/调试用，MyCallScreeningService 在用）
    private const val K_LAST_NUMBER = "last_number"
    private const val K_LAST_TIME = "last_time"

    // 发件箱同步去重（如果你做了 SmsSentObserver 会用到）
    private const val K_LAST_SENT_ID = "last_sent_id"

    fun isSilencePstn(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_SILENCE_PSTN, false)

    fun setSilencePstn(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(K_SILENCE_PSTN, v).apply()
    }

    fun getMode(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_MODE, "LAN") ?: "LAN"

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putString(K_MODE, mode).apply()
    }

    fun isServiceEnabled(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_SERVICE_ENABLED, true)

    fun setServiceEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(K_SERVICE_ENABLED, v).apply()
    }

    fun isManualPairEnabled(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_MANUAL_PAIR_ENABLED, false)

    fun setManualPairEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(K_MANUAL_PAIR_ENABLED, v).apply()
    }

    fun getPeerIp(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_PEER_IP, "") ?: ""

    fun setPeerIp(context: Context, ip: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putString(K_PEER_IP, ip.trim()).apply()
    }

    fun clearPeer(context: Context) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().remove(K_PEER_IP).apply()
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
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putString(K_HFP, v).apply()
    }

    fun isAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_AUTO_START_BOOT, false)

    fun setAutoStartOnBoot(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putBoolean(K_AUTO_START_BOOT, v).apply()
    }

    fun setLastIncoming(context: Context, number: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit()
            .putString(K_LAST_NUMBER, number)
            .putLong(K_LAST_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getLastNumber(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_LAST_NUMBER, "") ?: ""

    fun getLastTime(context: Context): Long =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getLong(K_LAST_TIME, 0L)

    fun getLastSeenSentId(context: Context): Long =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getLong(K_LAST_SENT_ID, 0L)

    fun setLastSeenSentId(context: Context, id: Long) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putLong(K_LAST_SENT_ID, id).apply()
    }
}
