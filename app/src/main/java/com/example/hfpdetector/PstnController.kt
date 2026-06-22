package com.example.hfpdetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager

object PstnController {

    private fun hasPerm(context: Context, p: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun answerRingingCall(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return

        if (!hasPerm(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            AppLog.i(context, "PSTN：缺少 ANSWER_PHONE_CALLS，无法接听运营商电话")
            return
        }

        val tm = context.getSystemService(TelecomManager::class.java)
        if (tm == null) {
            AppLog.i(context, "PSTN：TelecomManager=null")
            return
        }

        try {
            // 先静音铃声，避免继续响
            try { tm.silenceRinger() } catch (_: Throwable) {}
            tm.acceptRingingCall()
            AppLog.i(context, "PSTN：已调用 acceptRingingCall()")
        } catch (t: Throwable) {
            AppLog.i(context, "PSTN：接听失败：${t.javaClass.simpleName} ${t.message}")
        }
    }

    fun endCall(context: Context, reason: String) {
        if (Build.VERSION.SDK_INT < 28) {
            AppLog.i(context, "PSTN：API<28 无 endCall()，无法可靠挂断")
            return
        }

        if (!hasPerm(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            AppLog.i(context, "PSTN：缺少 ANSWER_PHONE_CALLS，无法挂断运营商电话")
            return
        }

        val tm = context.getSystemService(TelecomManager::class.java)
        if (tm == null) {
            AppLog.i(context, "PSTN：TelecomManager=null")
            return
        }

        try {
            val ok = tm.endCall()
            AppLog.i(context, "PSTN：已调用 endCall() reason=$reason ok=$ok")
        } catch (t: Throwable) {
            AppLog.i(context, "PSTN：挂断失败：${t.javaClass.simpleName} ${t.message}")
        }
    }
}
