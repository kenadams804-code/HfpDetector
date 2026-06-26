package com.example.hfpdetector

import android.content.Context
import android.telephony.TelephonyManager

fun isHasSimReady(context: Context): Boolean {
    val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
    return tm.simState == TelephonyManager.SIM_STATE_READY
}

fun Context.isHasSimReady(): Boolean = isHasSimReady(this)
