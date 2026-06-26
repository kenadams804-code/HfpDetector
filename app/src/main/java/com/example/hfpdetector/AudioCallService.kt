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

        /**
         * ✅ 第5个参数给默认值，兼容旧的 4 参调用（CoreService 不用改）
         */
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
            // ✅ 不要 startForegroundService（STOP 分支不会 startForeground）
            val i = Intent(context, AudioCallService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        fun setSpeaker(context: Context, on: Boolean) {
            speakerOn = on
            // ✅ 不要 startForegroundService（SET_SPEAKER 分支不会 startForeground）
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
                    session = AudioSession(this, peerIp, peerAudioPort, myAudioPort).also { it.start() }

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

    private fun applySpeakerRoute(on: Boolean) {
        try {
            val am = getSystemService(AudioManager::class.java) ?: return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isMicrophoneMute = false
            am.isSpeakerphoneOn = on

            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    if (on) am.setCommunicationDevice(speaker) else am.clearCommunicationDevice()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun acquireWifiLock() {
        try {
            if (wifiLock?.isHeld == true) return
            val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lancall:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLog.i(this, "AudioCallService：WifiLock acquired")
        } catch (_: Throwable) {}
    }

    private fun releaseWifiLock() {
        try { wifiLock?.release() } catch (_: Throwable) {}
        wifiLock = null
    }

    private fun startFg() {
        val n = NotificationCompat.Builder(this, AppConfig.CH_ONGOING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("LanCall 通话中")
            .setContentText("局域网语音通话进行中")
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
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
        releaseWifiLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
