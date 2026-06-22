package com.example.hfpdetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioCallService : Service() {

    companion object {
        private const val ACTION_START = "AudioCallService.START"
        private const val ACTION_STOP = "AudioCallService.STOP"

        fun start(context: Context, peerIp: String, peerAudioPort: Int, myAudioPort: Int) {
            val i = Intent(context, AudioCallService::class.java).setAction(ACTION_START)
            i.putExtra("peerIp", peerIp)
            i.putExtra("peerAudioPort", peerAudioPort)
            i.putExtra("myAudioPort", myAudioPort)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AudioCallService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var nm: NotificationManager
    private var session: AudioSession? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_START -> {
                    val peerIp = intent.getStringExtra("peerIp") ?: return START_NOT_STICKY
                    val peerAudioPort = intent.getIntExtra("peerAudioPort", 0)
                    val myAudioPort = intent.getIntExtra("myAudioPort", 0)
                    if (peerAudioPort == 0 || myAudioPort == 0) return START_NOT_STICKY

                    // ✅ 防崩：前台启动包一层 try/catch
                    if (!startFgSafely()) {
                        AppLog.i(this, "AudioCallService：startForeground 失败，停止服务（避免进程崩溃）")
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    session?.stop()
                    session = null

                    try {
                        session = AudioSession(this, peerIp, peerAudioPort, myAudioPort).also { it.start() }
                        AppLog.i(this, "AudioCallService：AudioSession 已启动 my=$myAudioPort peer=$peerAudioPort peerIp=$peerIp")
                    } catch (t: Throwable) {
                        AppLog.i(this, "AudioCallService：AudioSession 启动失败：${t.javaClass.simpleName} ${t.message}")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                ACTION_STOP -> {
                    AppLog.i(this, "AudioCallService：停止")
                    session?.stop()
                    session = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            START_STICKY
        } catch (t: Throwable) {
            // ✅ 最后兜底：任何异常都写日志，避免“按接听就退桌面”
            AppLog.i(this, "AudioCallService：onStartCommand 崩溃：${t.javaClass.simpleName} ${t.message}")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startFgSafely(): Boolean {
        return try {
            val n = NotificationCompat.Builder(this, AppConfig.CH_ONGOING)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("LanCall 通话中")
                .setContentText("局域网免提对讲进行中")
                .setOngoing(true)
                .setSilent(true)
                .build()

            if (Build.VERSION.SDK_INT >= 29) {
                // ✅ 只用 MICROPHONE，别用 PHONE_CALL（很多系统会限制/导致异常）
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(AppConfig.NID_ONGOING, n, type)
            } else {
                startForeground(AppConfig.NID_ONGOING, n)
            }
            true
        } catch (t: Throwable) {
            AppLog.i(this, "AudioCallService：startForeground 异常：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(
            NotificationChannel(
                AppConfig.CH_ONGOING,
                "LanCall 通话中",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
