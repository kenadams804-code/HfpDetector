package com.example.hfpdetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioCallService : Service() {

    companion object {
        private const val ACTION_START = "AudioCallService.START"
        private const val ACTION_STOP = "AudioCallService.STOP"
        private const val ACTION_SET_SPEAKER = "AudioCallService.SET_SPEAKER"

        @Volatile private var speakerOn: Boolean = true

        fun start(
            context: Context,
            peerIp: String,
            peerAudioPort: Int,
            myAudioPort: Int,
            speakerOnInit: Boolean = true
        ) {
            speakerOn = speakerOnInit
            val i = Intent(context, AudioCallService::class.java).setAction(ACTION_START)
            i.putExtra("peerIp", peerIp)
            i.putExtra("peerAudioPort", peerAudioPort)
            i.putExtra("myAudioPort", myAudioPort)
            i.putExtra("speakerOn", speakerOnInit)

            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AudioCallService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        fun setSpeaker(context: Context, on: Boolean) {
            speakerOn = on
            val i = Intent(context, AudioCallService::class.java).setAction(ACTION_SET_SPEAKER)
            i.putExtra("on", on)
            context.startService(i)
        }
    }

    private lateinit var nm: NotificationManager
    private var session: AudioSession? = null
    private var wifiLock: WifiManager.WifiLock? = null

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
                    speakerOn = intent.getBooleanExtra("speakerOn", speakerOn)

                    if (peerAudioPort == 0 || myAudioPort == 0) return START_NOT_STICKY

                    startFg()
                    acquireWifiLock()
                    applySpeakerRoute(speakerOn)

                    session?.stop()
                    session = AudioSession(this).also { 
                        it.start(peerIp, peerAudioPort, myAudioPort) 
                    }

                    AppLog.i(this, "AudioCallService：AudioSession 已启动 my=$myAudioPort peer=$peerAudioPort speakerOn=$speakerOn")
                }

                ACTION_SET_SPEAKER -> {
                    val on = intent.getBooleanExtra("on", true)
                    speakerOn = on
                    applySpeakerRoute(on)
                    AppLog.i(this, "AudioCallService：设置免提 speakerOn=$on")
                }

                ACTION_STOP -> {
                    AppLog.i(this, "AudioCallService：停止")
                    session?.stop()
                    session = null
                    releaseWifiLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            START_STICKY
        } catch (t: Throwable) {
            AppLog.i(this, "AudioCallService：兜底异常：${t.javaClass.simpleName} ${t.message}")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            releaseWifiLock()
            stopSelf()
            START_NOT_STICKY
        }
    }

    // 其余方法保持不变（applySpeakerRoute, acquireWifiLock 等）
    private fun applySpeakerRoute(on: Boolean) { /* ... */ }
    private fun acquireWifiLock() { /* ... */ }
    private fun releaseWifiLock() { /* ... */ }
    private fun startFg() { /* ... */ }
    private fun createChannel() { /* ... */ }

    override fun onDestroy() {
        session?.stop()
        session = null
        releaseWifiLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
