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
        when (intent?.action) {
            ACTION_START -> {
                val peerIp = intent.getStringExtra("peerIp") ?: return START_NOT_STICKY
                val peerAudioPort = intent.getIntExtra("peerAudioPort", 0)
                val myAudioPort = intent.getIntExtra("myAudioPort", 0)
                if (peerAudioPort == 0 || myAudioPort == 0) return START_NOT_STICKY

                startFg()

                session?.stop()
                session = AudioSession(this, peerIp, peerAudioPort, myAudioPort).also { it.start() }
            }
            ACTION_STOP -> {
                session?.stop()
                session = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startFg() {
        val n = NotificationCompat.Builder(this, AppConfig.CH_CALL)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("局域网通话中")
            .setContentText("免提对讲进行中")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            startForeground(AppConfig.NID_ONGOING, n, type)
        } else {
            startForeground(AppConfig.NID_ONGOING, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.CH_CALL, "LanCall 通话", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
