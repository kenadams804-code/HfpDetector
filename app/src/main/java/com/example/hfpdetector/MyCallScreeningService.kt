package com.example.hfpdetector

import android.telecom.Call
import android.telecom.CallScreeningService

class MyCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = try {
            callDetails.handle?.schemeSpecificPart ?: "未知号码"
        } catch (_: Throwable) {
            "未知号码"
        }

        // 记录一份“最近来电号码/时间”，用于界面显示（更像应用，不用看 true/false）
        Prefs.setLastIncoming(this, number)

        // 触发：把号码推给无卡机
        CoreService.notifyIncomingPstn(this, number)

        val silence = Prefs.isSilencePstn(this)

        // 不拦截这通电话：只是监听并触发
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .setSilenceCall(silence) // 开关控制：有卡机正常响铃/尽量静音
            .build()

        respondToCall(callDetails, response)
    }
}
