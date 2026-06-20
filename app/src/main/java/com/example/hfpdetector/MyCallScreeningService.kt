package com.example.hfpdetector

import android.os.Build
import android.telecom.CallScreeningService
import android.telecom.Call
import java.util.Locale

class MyCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = try {
            callDetails.handle?.schemeSpecificPart ?: "未知号码"
        } catch (_: Throwable) {
            "未知号码"
        }

        // 通知 CoreService：外部来电触发
        CoreService.notifyIncomingPstn(this, number)

        // 不拦截这通电话（只是“监听并触发”）
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            // 你如果希望“有卡机不响铃”，可以改成 true（但不同机型效果不同）
            .setSilenceCall(false)
            .build()

        respondToCall(callDetails, response)
    }
}
