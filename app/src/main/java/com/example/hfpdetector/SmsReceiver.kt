package com.example.hfpdetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (msgs.isEmpty()) return

        val address = msgs.firstOrNull()?.originatingAddress ?: "未知号码"
        val body = msgs.joinToString(separator = "") { it.messageBody ?: "" }
        val ts = msgs.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val msgId = UUID.randomUUID().toString()

        // 有卡机自己也存一份（LanCall 短信箱）
        HistoryStore.insertSmsIn(context, msgId, address, body, peerIp = "(pstn)", ts = ts)

        // 转发到无卡机
        CoreService.forwardSmsToPeer(context, msgId, address, body, ts)
    }
}
