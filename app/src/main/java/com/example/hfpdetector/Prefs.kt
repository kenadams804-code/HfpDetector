package com.example.hfpdetector

import android.content.Context

object Prefs {
    private const val SP = "lancall_sp"
    private const val K_SILENCE_PSTN = "silence_pstn"  // 有卡机来电是否静音

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
}
