package com.example.hfpdetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // App 内开关关闭：不开机自启
        if (!Prefs.isAutoStartOnBoot(context)) return

        // Android 13+：如果通知权限被关掉，前台服务通知可能无法显示，很多机型会导致启动不稳定
        // 为了稳妥，这里要求通知可用才启动（你也可以删除这段强制启动）
        if (Build.VERSION.SDK_INT >= 33) {
            val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!enabled) return
        }

        CoreService.start(context)
    }
}
