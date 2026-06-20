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

        // 触发：把号码推给无卡机
        CoreService.notifyIncomingPstn(this, number)

        val silence = Prefs.isSilencePstn(this)

        // 不拦截这通电话：只是监听并触发
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .setSilenceCall(silence) // 这里由开关决定
            .build()

        respondToCall(callDetails, response)
    }
}
