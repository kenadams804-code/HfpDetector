package com.example.hfpdetector

import android.content.Context

object Prefs {
    private const val SP = "lancall_sp"

    private const val K_SILENCE_PSTN = "silence_pstn"      // 有卡机来电是否尽量静音
    private const val K_LAST_NUMBER = "last_number"        // 最近一次系统回调到的来电号码
    private const val K_LAST_TIME = "last_time"            // 最近一次系统回调时间戳
    private const val K_AUTO_START_BOOT = "auto_start_boot"// 开机自启开关

    fun isSilencePstn(context: Context): Boolean {
        return context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getBoolean(K_SILENCE_PSTN, false)
    }

    fun setSilencePstn(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(K_SILENCE_PSTN, v)
            .apply()
    }

    fun setLastIncoming(context: Context, number: String) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit()
            .putString(K_LAST_NUMBER, number)
            .putLong(K_LAST_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getLastNumber(context: Context): String {
        return context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString(K_LAST_NUMBER, "") ?: ""
    }

    fun getLastTime(context: Context): Long {
        return context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getLong(K_LAST_TIME, 0L)
    }

    fun isAutoStartOnBoot(context: Context): Boolean {
        return context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getBoolean(K_AUTO_START_BOOT, false)
    }

    fun setAutoStartOnBoot(context: Context, v: Boolean) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(K_AUTO_START_BOOT, v)
            .apply()
    }
}
