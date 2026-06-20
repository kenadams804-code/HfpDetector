package com.example.hfpdetector

data class PendingInvite(
    val callId: String,
    val number: String,
    val peerIp: String,
    val peerControlPort: Int,
    val peerAudioPort: Int,   // 对方（发起方）音频接收端口
    val mySuggestedAudioPort: Int = 0
)

object CallState {
    @Volatile var incoming: PendingInvite? = null
}
