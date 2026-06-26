package com.example.hfpdetector

import android.content.Context
import android.telephony.TelephonyManager

/**
 * 允许这样用：
 *   val tm = getSystemService(TelephonyManager::class.java)
 *   tm?.isHasSimReady() == true
 */
fun TelephonyManager.isHasSimReady(): Boolean {
    return simState == TelephonyManager.SIM_STATE_READY
}

fun TelephonyManager?.isHasSimReady(): Boolean {
    return this != null && this.isHasSimReady()
}

/**
 * 允许这样用：
 *   applicationContext.isHasSimReady()
 *   this.isHasSimReady()   (Activity 也是 Context)
 */
fun Context.isHasSimReady(): Boolean {
    val tm = getSystemService(TelephonyManager::class.java)
    return tm.isHasSimReady()
}

/**
 * 允许这样用：
 *   isHasSimReady(this)
 */
fun isHasSimReady(context: Context): Boolean = context.isHasSimReady()
