package com.example.hfpdetector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_box")
data class SmsEntity(
    @PrimaryKey val msgId: String,
    val direction: String, // "IN"
    val address: String,
    val body: String,
    val peerIp: String,
    val ts: Long,
    val status: String     // "RECEIVED"
)
