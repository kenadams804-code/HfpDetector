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

        fun start(context: Context) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun notifyIncomingPstn(context: Context, number: String) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_INCOMING_PSTN)
            i.putExtra("number", number)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var nm: NotificationManager
    private var socket: DatagramSocket? = null
    private var running = true

    private var multicastLock: WifiManager.MulticastLock? = null

    // sender 端：发现 receiver 的 IP
    @Volatile private var peerIp: InetAddress? = null
    @Volatile private var peerControlPort: Int = AppConfig.CONTROL_PORT

    // sender 端：当前通话参数
    @Volatile private var myAudioPort: Int = 0
    @Volatile private var callId: String? = null

    private var ringtone: Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        createChannels()
        startForeground(AppConfig.NID_PERSIST, buildPersistNotification("启动中..."))
        startNetworking()
        updatePersist("服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING_PSTN -> {
                val num = intent.getStringExtra("number") ?: "未知号码"
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isHasSimReady(): Boolean {
        val tm = getSystemService(TelephonyManager::class.java)
        return tm?.simState == TelephonyManager.SIM_STATE_READY
    }

    private fun startNetworking() {
        socket = DatagramSocket(AppConfig.CONTROL_PORT).apply { broadcast = true }

        // 接收线程（两端都需要）
        thread(name = "ctrl-recv") {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket?.receive(p) ?: continue
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    onControlMessage(msg, p.address)
                } catch (_: Throwable) {
                }
            }
        }

        if (!isHasSimReady()) {
            // 无卡机：receiver 模式 —— 周期广播“我在线”
            acquireMulticast()
            startHelloBroadcast()
            updatePersist("接听端：等待来电触发")
        } else {
            // 有卡机：sender 模式 —— 等待发现 receiver
            updatePersist("有卡端：等待发现接听端（同一 Wi-Fi）")
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
                    val bcast = NetUtils.getBroadcastAddress(this@CoreService) ?: InetAddress.getByName("255.255.255.255")
                    val obj = JSONObject()
                        .put("type", "HELLO")
                        .put("controlPort", AppConfig.CONTROL_PORT)
                    val data = obj.toString().toByteArray(Charsets.UTF_8)
                    val p = DatagramPacket(data, data.size, bcast, AppConfig.CONTROL_PORT)
                    socket?.send(p)
                } catch (_: Throwable) {
                }
                Thread.sleep(AppConfig.HELLO_INTERVAL_MS)
            }
        }
    }

    private fun onControlMessage(msg: String, fromIp: InetAddress) {
        val obj = try { JSONObject(msg) } catch (_: Throwable) { return }
        when (obj.optString("type")) {
            "HELLO" -> {
                // 仅 sender 需要记录 peer
                if (isHasSimReady()) {
                    peerIp = fromIp
                    peerControlPort = obj.optInt("controlPort", AppConfig.CONTROL_PORT)
                    updatePersist("有卡端：已发现接听端 $fromIp")
                }
            }
            "INVITE" -> {
                // 仅 receiver 处理来电邀请
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

                    ringAndShowIncoming(number)
                }
            }
            "ACCEPT" -> {
                // 仅 sender 收到：对方接受，开始通话
                if (isHasSimReady()) {
                    val cid = obj.optString("callId", "")
                    val receiverAudioPort = obj.optInt("audioPort", 0)
                    val receiverIp = fromIp.hostAddress

                    if (cid.isNotBlank() && cid == callId && receiverAudioPort != 0 && myAudioPort != 0) {
                        AudioCallService.start(
                            context = this,
                            peerIp = receiverIp,
                            peerAudioPort = receiverAudioPort,
                            myAudioPort = myAudioPort
                        )
                        updatePersist("有卡端：通话中（对讲）")
                    }
                }
            }
            "DECLINE" -> {
                if (isHasSimReady()) {
                    updatePersist("有卡端：对方拒绝")
                }
            }
            "HANGUP" -> {
                AudioCallService.stop(this)
                updatePersist(if (isHasSimReady()) "有卡端：已挂断" else "接听端：已挂断")
            }
        }
    }

    private fun handleIncomingPstn(number: String) {
        // sender 端：收到外部来电事件 -> 发 INVITE 给 receiver
        if (!isHasSimReady()) return

        val peer = peerIp
        if (peer == null) {
            updatePersist("有卡端：未发现接听端（确认两机同 Wi-Fi，且无卡机已点启动服务）")
            return
        }

        val cid = UUID.randomUUID().toString()
        callId = cid

        myAudioPort = Random.nextInt(45000, 45999)

        val obj = JSONObject()
            .put("type", "INVITE")
            .put("callId", cid)
            .put("number", number)
            .put("audioPort", myAudioPort)           // 我方音频端口（对方要发给我）
            .put("controlPort", AppConfig.CONTROL_PORT)

        val data = obj.toString().toByteArray(Charsets.UTF_8)
        val p = DatagramPacket(data, data.size, peer, peerControlPort)
        socket?.send(p)

        updatePersist("有卡端：已推送来电到接听端（号码：$number）")
    }

    private fun ringAndShowIncoming(number: String) {
        // 1) 全屏来电通知（点击接听进入 IncomingCallActivity）
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(this, AppConfig.CH_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("局域网来电")
            .setContentText("号码：$number")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()

        nm.notify(AppConfig.NID_INCOMING, n)

        // 2) 响铃
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
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Throwable) {}
        ringtone = null
        nm.cancel(AppConfig.NID_INCOMING)
    }

    private fun updatePersist(text: String) {
        nm.notify(AppConfig.NID_PERSIST, buildPersistNotification(text))
    }

    private fun buildPersistNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AppConfig.CH_PERSIST)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("LanCall 常驻服务")
            .setContentText(text)
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

    fun stopIncomingUiAndRingtone() {
        stopRinging()
    }
}
