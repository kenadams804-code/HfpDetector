package com.example.hfpdetector

object AppConfig {
    const val CONTROL_PORT = 39090

    /**
     * ✅ 关键修改：
     * 原来 1500ms 的广播太频繁，会明显拖慢整张 Wi‑Fi（你说的“全网卡顿/200KB/s”非常符合）
     * 先改成 10s；后面我们在 CoreService 里再做“连接后停止广播”的逻辑。
     */
    const val HELLO_INTERVAL_MS = 10_000L

    const val CH_PERSIST = "lancall_persist"

    // 关键：换新通道ID，避免旧通道被系统/用户降级后无法全屏弹出
    const val CH_CALL = "lancall_call_v2"

    const val NID_PERSIST = 1001
    const val NID_INCOMING = 2001
    const val NID_ONGOING = 2002
}
