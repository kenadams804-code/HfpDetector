package com.example.hfpdetector

import android.content.Context
import android.telephony.TelephonyManager

/**
 * 用法：
 *   val hasSim = applicationContext.isHasSimReady()
 */
fun Context.isHasSimReady(): Boolean {
    val tm = getSystemService(TelephonyManager::class.java) ?: return false
    return tm.simState == TelephonyManager.SIM_STATE_READY
}
