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
import androidx.core.app.NotificationManagerCompat
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
        private const val ACTION_STOP = "CoreService.STOP"
        private const val ACTION_INCOMING_PSTN = "CoreService.INCOMING_PSTN"
        private const val ACTION_STOP_RING = "CoreService.STOP_RING"
        private const val ACTION_TEST_INVITE = "CoreService.TEST_INVITE"

        fun start(context: Context) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_STOP)
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

        fun sendTestInvite(context: Context) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_TEST_INVITE)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var nm: NotificationManager
    private var socket: DatagramSocket? = null
    @Volatile private var running = true

    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var peerIp: InetAddress? = null
    @Volatile private var peerControlPort: Int = AppConfig.CONTROL_PORT

    @Volatile private var myAudioPort: Int = 0
    @Volatile private var callId: String? = null

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    // 节流日志，避免刷屏
    private var lastPongLogTs: Long = 0
    private var lastPingLogTs: Long = 0

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        vibrator = getSystemVibrator()
        createChannels()

        startForeground(AppConfig.NID_PERSIST, buildPersistNotification())

        AppLog.i(this, "CoreService 启动")
        startNetworking()

        if (isHasSimReady()) {
            applyManualPairIfEnabled()
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> { /* no-op */ }

            ACTION_STOP -> {
                AppLog.i(this, "CoreService 停止")
                stopRinging()
                running = false
                try { socket?.close() } catch (_: Throwable) {}
                try { multicastLock?.release() } catch (_: Throwable) {}
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_RING -> {
                stopRinging()
                AppLog.i(this, "停止响铃/震动")
            }

            ACTION_INCOMING_PSTN -> {
                val num = intent.getStringExtra("number") ?: "未知号码"
                AppLog.i(this, "收到系统来电回调：$num")
                handleInviteSend(number = num, isTest = false)
            }

            ACTION_TEST_INVITE -> {
                AppLog.i(this, "手动触发：发送测试 INVITE")
                handleInviteSend(number = "测试来电", isTest = true)
            }
        }
        return START_STICKY
    }

    private fun isHasSimReady(): Boolean {
        val tm = getSystemService(TelephonyManager::class.java)
        return tm?.simState == TelephonyManager.SIM_STATE_READY
    }

    private fun startNetworking() {
        socket = DatagramSocket(AppConfig.CONTROL_PORT).apply { broadcast = true }

        // 接收线程
        thread(name = "ctrl-recv") {
            val buf = ByteArray(8192)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket?.receive(p) ?: continue
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    onControlMessage(msg, p.address, p.port)
                } catch (_: Throwable) {}
            }
        }

        if (!isHasSimReady()) {
            // 无卡机：广播 HELLO 供发现
            acquireMulticast()
            startHelloBroadcast()
            AppLog.i(this, "接听端：开始广播 HELLO")
        } else {
            AppLog.i(this, "有卡端：启动 PING 心跳")
            startPingLoop()
        }
    }

    private fun applyManualPairIfEnabled() {
        val enabled = Prefs.isManualPairEnabled(this)
        val ip = Prefs.getPeerIp(this)
        if (enabled && ip.isNotBlank()) {
            try {
                peerIp = InetAddress.getByName(ip)
                peerControlPort = AppConfig.CONTROL_PORT
            } catch (_: Throwable) {}
        }
    }

    private fun startPingLoop() {
        thread(name = "ping-loop") {
            while (running) {
                try {
                    applyManualPairIfEnabled()
                    val peer = peerIp
                    if (peer != null) {
                        val obj = JSONObject().put("type", "PING").put("t", System.currentTimeMillis())
                        sendJson(peer, peerControlPort, obj)

                        val now = System.currentTimeMillis()
                        if (now - lastPingLogTs > 10_000) {
                            lastPingLogTs = now
                            AppLog.i(this, "有卡端：发送 PING -> ${peer.hostAddress}")
                        }
                    }
                } catch (_: Throwable) {}
                Thread.sleep(2000)
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

    private fun onControlMessage(msg: String, fromIp: InetAddress, fromPort: Int) {
        val obj = try { JSONObject(msg) } catch (_: Throwable) { return }

        when (obj.optString("type")) {
            "HELLO" -> {
                if (isHasSimReady() && !Prefs.isManualPairEnabled(this)) {
                    peerIp = fromIp
                    peerControlPort = obj.optInt("controlPort", AppConfig.CONTROL_PORT)
                    Prefs.markPeerSeen(this, fromIp.hostAddress)
                }
            }

            "PING" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val pong = JSONObject().put("type", "PONG").put("t", obj.optLong("t", System.currentTimeMillis()))
                sendJson(fromIp, fromPort, pong)
            }

            "PONG" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val now = System.currentTimeMillis()
                if (now - lastPongLogTs > 10_000) {
                    lastPongLogTs = now
                    AppLog.i(this, "有卡端：收到 PONG <- ${fromIp.hostAddress}")
                }
            }

            "PING_TEST" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val nonce = obj.optString("nonce", "")
                val resp = JSONObject().put("type", "PONG_TEST").put("nonce", nonce)
                sendJson(fromIp, fromPort, resp)
                AppLog.i(this, "收到 PING_TEST，已回复 PONG_TEST -> ${fromIp.hostAddress}:$fromPort")
            }

            /**
             * ✅ INVITE：收到就处理（不依赖 simState），并回 ACK
             * ACK 回两个端口：fromPort + senderCtrlPort，排除“回错端口”
             */
            "INVITE" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)

                val number = obj.optString("number", "未知号码")
                val cid = obj.optString("callId", UUID.randomUUID().toString())
                val senderAudioPort = obj.optInt("audioPort", 0)
                val senderCtrlPort = obj.optInt("controlPort", AppConfig.CONTROL_PORT)
                val isTest = obj.optBoolean("test", false)

                val ack = JSONObject()
                    .put("type", "INVITE_ACK")
                    .put("callId", cid)
                    .put("test", isTest)

                sendJson(fromIp, fromPort, ack)
                sendJson(fromIp, senderCtrlPort, ack)

                CallState.incoming = PendingInvite(
                    callId = cid,
                    number = number,
                    peerIp = fromIp.hostAddress,
                    peerControlPort = senderCtrlPort,
                    peerAudioPort = senderAudioPort
                )

                AppLog.i(this, "接听端：收到 INVITE number=$number test=$isTest from=$fromIp")

                stopRinging()
                ringAndShowIncoming(number)
            }

            "INVITE_ACK" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                AppLog.i(this, "有卡端：收到 INVITE_ACK <- ${fromIp.hostAddress} callId=${obj.optString("callId")}")
            }

            // 其它类型（你项目里已有 ACCEPT/HANGUP/DECLINE 可继续加回，这里不影响弹窗链路）
        }
    }

    private fun sendJson(ip: InetAddress, port: Int, obj: JSONObject) {
        try {
            val data = obj.toString().toByteArray(Charsets.UTF_8)
            val p = DatagramPacket(data, data.size, ip, port)
            socket?.send(p)
        } catch (_: Throwable) {}
    }

    /**
     * ✅ INVITE 发送：后台线程 + 重发3次（避免 UDP 单包丢失）
     */
    private fun handleInviteSend(number: String, isTest: Boolean) {
        if (!isHasSimReady()) {
            AppLog.i(this, "本机不是有卡端，忽略发送 INVITE")
            return
        }

        thread(name = "send-invite") {
            applyManualPairIfEnabled()
            val peer = peerIp
            if (peer == null) {
                AppLog.i(this, "有卡端：未找到接听端（请先配对/确保对端在线）")
                return@thread
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
                .put("test", isTest)

            val delays = longArrayOf(0, 120, 300)
            for (d in delays) {
                try { Thread.sleep(d) } catch (_: Throwable) {}
                sendJson(peer, peerControlPort, obj)
            }

            AppLog.i(this, "有卡端：发送 INVITE x3 -> ${peer.hostAddress} number=$number test=$isTest")
        }
    }

    /**
     * 来电弹窗：先发 FullScreen 通知，再尝试 startActivity。
     * 你现在看到“震动但不弹窗”，多数是 ROM/通知通道策略导致 FullScreen 不触发。
     * 这里把关键状态写入日志，便于判断原因。
     */
    private fun ringAndShowIncoming(number: String) {
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val chImportance = if (Build.VERSION.SDK_INT >= 26) nm.getNotificationChannel(AppConfig.CH_CALL)?.importance else null
        val canFsi = if (Build.VERSION.SDK_INT >= 34) {
            try { nm.canUseFullScreenIntent() } catch (_: Throwable) { true }
        } else true

        AppLog.i(this, "来电弹窗检查：notifEnabled=$notificationsEnabled channelImportance=$chImportance canUseFullScreenIntent=$canFsi")

        try {
            val n = NotificationCompat.Builder(this, AppConfig.CH_CALL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("来电")
                .setContentText(number)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
            nm.notify(AppConfig.NID_INCOMING, n)
            AppLog.i(this, "已发送来电通知（含FullScreenIntent）")
        } catch (t: Throwable) {
            AppLog.i(this, "发送来电通知失败：${t.javaClass.simpleName} ${t.message}")
        }

        try {
            startActivity(fullScreenIntent)
            AppLog.i(this, "已尝试直接 startActivity 拉起来电界面")
        } catch (t: Throwable) {
            AppLog.i(this, "startActivity 拉起失败：${t.javaClass.simpleName} ${t.message}")
        }

        startRinging()
    }

    private fun startRinging() {
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
                getSystemService(VibratorManager::class.java)?.defaultVibrator
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
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.CH_PERSIST, "LanCall 常驻", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(AppConfig.CH_CALL, "LanCall 来电", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
