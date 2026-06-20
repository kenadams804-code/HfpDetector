package com.example.hfpdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.random.Random

class CoreService : Service() {

    companion object {
        private const val ACTION_START = "CoreService.START"
        private const val ACTION_INCOMING_PSTN = "CoreService.INCOMING_PSTN"
        private const val ACTION_STOP_RING = "CoreService.STOP_RING"

        fun start(context: Context) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun notifyIncomingPstn(context: Context, number: String) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_INCOMING_PSTN)
            i.putExtra("number", number)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stopRingingNow(context: Context) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_STOP_RING)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var nm: NotificationManager
    private var socket: DatagramSocket? = null
    private var running = true

    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var peerIp: InetAddress? = null
    @Volatile private var peerControlPort: Int = AppConfig.CONTROL_PORT

    @Volatile private var myAudioPort: Int = 0
    @Volatile private var callId: String? = null

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        vibrator = getSystemVibrator()
        createChannels()

        // 通知栏：只显示固定信息（不再滚动各种状态）
        startForeground(AppConfig.NID_PERSIST, buildPersistNotification())

        AppLog.i(this, "CoreService 启动")
        startNetworking()

        // 有卡机：如果启用了手动配对，则直接使用保存的 IP
        if (isHasSimReady()) {
            applyManualPairIfEnabled()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // no-op
            }
            ACTION_STOP_RING -> {
                stopRinging()
                AppLog.i(this, "停止响铃/震动")
            }
            ACTION_INCOMING_PSTN -> {
                val num = intent.getStringExtra("number") ?: "未知号码"
                AppLog.i(this, "收到系统来电回调：$num")
                handleIncomingPstn(num)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Throwable) {}
        try { multicastLock?.release() } catch (_: Throwable) {}
        stopRinging()
        AppLog.i(this, "CoreService 销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isHasSimReady(): Boolean {
        val tm = getSystemService(TelephonyManager::class.java)
        return tm?.simState == TelephonyManager.SIM_STATE_READY
    }

    private fun startNetworking() {
        socket = DatagramSocket(AppConfig.CONTROL_PORT).apply { broadcast = true }

        // 接收线程
        thread(name = "ctrl-recv") {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket?.receive(p) ?: continue
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    onControlMessage(msg, p.address)
                } catch (_: Throwable) {}
            }
        }

        if (!isHasSimReady()) {
            // 无卡机：receiver 广播“我在线”（用于自动发现）
            acquireMulticast()
            startHelloBroadcast()
            AppLog.i(this, "接听端：开始广播 HELLO 供有卡机发现")
        } else {
            AppLog.i(this, "有卡端：等待发现接听端（或使用手动配对）")
        }
    }

    private fun applyManualPairIfEnabled() {
        val enabled = Prefs.isManualPairEnabled(this)
        val ip = Prefs.getPeerIp(this)
        if (enabled && ip.isNotBlank()) {
            try {
                peerIp = InetAddress.getByName(ip)
                peerControlPort = AppConfig.CONTROL_PORT
                AppLog.i(this, "有卡端：启用手动配对 peerIp=$ip")
            } catch (t: Throwable) {
                AppLog.i(this, "有卡端：手动配对 IP 无效：$ip  err=${t.message}")
            }
        }
    }

    private fun acquireMulticast() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifi.createMulticastLock("lancall-multi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startHelloBroadcast() {
        thread(name = "hello-bcast") {
            while (running) {
                try {
                    val bcast = NetUtils.getBroadcastAddress(this@CoreService)
                        ?: InetAddress.getByName("255.255.255.255")
                    val obj = JSONObject()
                        .put("type", "HELLO")
                        .put("controlPort", AppConfig.CONTROL_PORT)
                    val data = obj.toString().toByteArray(Charsets.UTF_8)
                    val p = DatagramPacket(data, data.size, bcast, AppConfig.CONTROL_PORT)
                    socket?.send(p)
                } catch (_: Throwable) {}
                Thread.sleep(AppConfig.HELLO_INTERVAL_MS)
            }
        }
    }

    private fun onControlMessage(msg: String, fromIp: InetAddress) {
        val obj = try { JSONObject(msg) } catch (_: Throwable) { return }
        when (obj.optString("type")) {

            "HELLO" -> {
                // 自动发现：只有在“未启用手动配对”时才更新 peer
                if (isHasSimReady() && !Prefs.isManualPairEnabled(this)) {
                    peerIp = fromIp
                    peerControlPort = obj.optInt("controlPort", AppConfig.CONTROL_PORT)
                    AppLog.i(this, "有卡端：自动发现接听端 $fromIp")
                }
            }

            "INVITE" -> {
                // receiver 收到邀请 -> 弹无卡机来电界面
                if (!isHasSimReady()) {
                    val number = obj.optString("number", "未知号码")
                    val cid = obj.optString("callId", UUID.randomUUID().toString())
                    val senderAudioPort = obj.optInt("audioPort", 0)
                    val senderCtrlPort = obj.optInt("controlPort", AppConfig.CONTROL_PORT)

                    CallState.incoming = PendingInvite(
                        callId = cid,
                        number = number,
                        peerIp = fromIp.hostAddress,
                        peerControlPort = senderCtrlPort,
                        peerAudioPort = senderAudioPort
                    )

                    AppLog.i(this, "接听端：收到 INVITE 号码=$number from=$fromIp")
                    stopRinging()
                    ringAndShowIncoming(number)
                }
            }

            "ACCEPT" -> {
                // sender 收到对方接受 -> 开始音频
                if (isHasSimReady()) {
                    val cid = obj.optString("callId", "")
                    val receiverAudioPort = obj.optInt("audioPort", 0)
                    val receiverIp = fromIp.hostAddress

                    if (cid.isNotBlank() && cid == callId && receiverAudioPort != 0 && myAudioPort != 0) {
                        AppLog.i(this, "有卡端：对方接听，开始对讲 receiver=$receiverIp:$receiverAudioPort")
                        AudioCallService.start(
                            context = this,
                            peerIp = receiverIp,
                            peerAudioPort = receiverAudioPort,
                            myAudioPort = myAudioPort
                        )
                    }
                }
            }

            "DECLINE" -> {
                if (isHasSimReady()) AppLog.i(this, "有卡端：对方拒绝")
            }

            "HANGUP" -> {
                AudioCallService.stop(this)
                AppLog.i(this, "通话挂断")
            }
        }
    }

    private fun handleIncomingPstn(number: String) {
        if (!isHasSimReady()) return

        // 每次来电触发时，再读取一次“手动配对”配置（避免你在设置页改了但服务没重启）
        applyManualPairIfEnabled()

        val peer = peerIp
        if (peer == null) {
            AppLog.i(this, "有卡端：未找到接听端（请确保无卡机已启动服务，或手动配对 IP）")
            return
        }

        val cid = UUID.randomUUID().toString()
        callId = cid
        myAudioPort = Random.nextInt(45000, 45999)

        val obj = JSONObject()
            .put("type", "INVITE")
            .put("callId", cid)
            .put("number", number)
            .put("audioPort", myAudioPort)
            .put("controlPort", AppConfig.CONTROL_PORT)

        val data = obj.toString().toByteArray(Charsets.UTF_8)
        val p = DatagramPacket(data, data.size, peer, peerControlPort)
        socket?.send(p)

        AppLog.i(this, "有卡端：已发送 INVITE 到接听端 ${peer.hostAddress}")
    }

    private fun ringAndShowIncoming(number: String) {
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 来电通知（必须保留：用于全屏弹窗）
        val n = NotificationCompat.Builder(this, AppConfig.CH_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("来电")
            .setContentText(number)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()

        nm.notify(AppConfig.NID_INCOMING, n)
        startRinging()
    }

    private fun startRinging() {
        // 铃声
        try {
            if (ringtone?.isPlaying == true) return
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri).apply {
                if (Build.VERSION.SDK_INT >= 21) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
        } catch (_: Throwable) {}

        // 震动（循环）
        startVibrateLoop()
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Throwable) {}
        ringtone = null
        stopVibrate()
        nm.cancel(AppConfig.NID_INCOMING)
    }

    private fun getSystemVibrator(): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun startVibrateLoop() {
        try {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return
            val pattern = longArrayOf(0, 450, 350, 450, 1100)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        } catch (_: Throwable) {}
    }

    private fun stopVibrate() {
        try { vibrator?.cancel() } catch (_: Throwable) {}
    }

    private fun buildPersistNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AppConfig.CH_PERSIST)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("LanCall")
            .setContentText("正在后台运行")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return

        // 常驻通知：低打扰
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.CH_PERSIST, "LanCall 常驻", NotificationManager.IMPORTANCE_LOW)
        )
        // 来电：高优先级
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.CH_CALL, "LanCall 来电", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
