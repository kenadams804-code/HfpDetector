package com.example.hfpdetector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_log")
data class CallLogEntity(
    @PrimaryKey val callId: String,
    val direction: String, // "IN" / "OUT"
    val number: String,
    val peerIp: String,
    val isTest: Boolean,
    val state: String,     // "RINGING" "ANSWERED" "DECLINED" "ENDED"
    val ts: Long,
    val lastUpdateTs: Long
)
