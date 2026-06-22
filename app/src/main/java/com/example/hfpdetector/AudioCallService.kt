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

                    startFgSafely()

                    session?.stop()
                    session = null

                    session = AudioSession(this, peerIp, peerAudioPort, myAudioPort).also { it.start() }
                    AppLog.i(this, "AudioCallService：AudioSession 已启动 my=$myAudioPort peer=$peerAudioPort")
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
            AppLog.i(this, "AudioCallService：崩溃兜底：${t.javaClass.simpleName} ${t.message}")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startFgSafely() {
        val n = NotificationCompat.Builder(this, AppConfig.CH_ONGOING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("LanCall 通话中")
            .setContentText("局域网免提对讲进行中")
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            // ✅ 只使用 MICROPHONE（不要 PHONE_CALL）
            startForeground(AppConfig.NID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(AppConfig.NID_ONGOING, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.CH_ONGOING, "LanCall 通话中", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
