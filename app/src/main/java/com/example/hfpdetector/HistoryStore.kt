package com.example.hfpdetector

import android.content.Context
import com.example.hfpdetector.db.AppDb
import com.example.hfpdetector.db.CallLogEntity
import com.example.hfpdetector.db.SmsEntity
import kotlin.concurrent.thread

object HistoryStore {

    fun upsertCall(
        context: Context,
        callId: String,
        direction: String,
        number: String,
        peerIp: String,
        isTest: Boolean,
        state: String,
        ts: Long = System.currentTimeMillis()
    ) {
        thread {
            try {
                val now = System.currentTimeMillis()
                AppDb.get(context).callLogDao().upsert(
                    CallLogEntity(
                        callId = callId,
                        direction = direction,
                        number = number,
                        peerIp = peerIp,
                        isTest = isTest,
                        state = state,
                        ts = ts,
                        lastUpdateTs = now
                    )
                )
            } catch (_: Throwable) {}
        }
    }

    fun updateCallState(context: Context, callId: String, state: String) {
        thread {
            try {
                AppDb.get(context).callLogDao().updateState(callId, state, System.currentTimeMillis())
            } catch (_: Throwable) {}
        }
    }

    fun upsertSmsIn(context: Context, msgId: String, address: String, body: String, peerIp: String, status: String, ts: Long) {
        thread {
            try {
                AppDb.get(context).smsDao().upsert(
                    SmsEntity(
                        msgId = msgId,
                        direction = "IN",
                        address = address,
                        body = body,
                        peerIp = peerIp,
                        ts = ts,
                        status = status
                    )
                )
            } catch (_: Throwable) {}
        }
    }
}
