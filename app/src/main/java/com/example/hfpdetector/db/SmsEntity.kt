package com.example.hfpdetector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_box")
data class SmsEntity(
    @PrimaryKey val msgId: String,
    val direction: String, // "IN" / "OUT"
    val address: String,   // 短信号码
    val body: String,
    val peerIp: String,
    val ts: Long,
    val status: String     // "RECEIVED" "FORWARDED"
)
