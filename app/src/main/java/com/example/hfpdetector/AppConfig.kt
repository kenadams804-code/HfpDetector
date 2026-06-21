package com.example.hfpdetector

object AppConfig {
    const val CONTROL_PORT = 39090
    const val HELLO_INTERVAL_MS = 1500L

    const val CH_PERSIST = "lancall_persist"

    // 关键：换新通道ID，避免旧通道被系统/用户降级后无法全屏弹出
    const val CH_CALL = "lancall_call_v2"

    const val NID_PERSIST = 1001
    const val NID_INCOMING = 2001
    const val NID_ONGOING = 2002
}
