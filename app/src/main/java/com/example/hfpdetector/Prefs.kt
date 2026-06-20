package com.example.hfpdetector

import android.content.Context

object Prefs {
    private const val SP = "lancall_sp"

    private const val K_SILENCE_PSTN = "silence_pstn"
    private const val K_LAST_NUMBER = "last_number"
    private const val K_LAST_TIME = "last_time"
    private const val K_AUTO_START_BOOT = "auto_start_boot"

    // 手动配对（有卡机用）
    private const val K_MANUAL_PAIR_ENABLED = "manual_pair_enabled"
    private const val K_PEER_IP = "peer_ip"

    fun isSilencePstn(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_SILENCE_PSTN, false)

    fun setSilencePstn(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(K_SILENCE_PSTN, v).apply()
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

    fun isAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_AUTO_START_BOOT, false)

    fun setAutoStartOnBoot(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(K_AUTO_START_BOOT, v).apply()
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

    fun clearPeer(context: Context) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().remove(K_PEER_IP).apply()
    }
}
