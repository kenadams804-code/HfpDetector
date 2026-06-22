package com.example.hfpdetector

object AppConfig {
    const val CONTROL_PORT = 39090

    // ✅ 你已确认要降低 HELLO 频率
    const val HELLO_INTERVAL_MS = 10_000L

    const val CH_PERSIST = "lancall_persist"
    const val CH_CALL = "lancall_call_v2"
    const val CH_ONGOING = "lancall_ongoing_v1"

    const val NID_PERSIST = 1001
    const val NID_INCOMING = 2001
    const val NID_ONGOING = 2002
}
