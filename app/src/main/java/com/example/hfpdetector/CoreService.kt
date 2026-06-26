package com.example.hfpdetector

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

class CoreService : Service() {

    companion object {
        private const val ACTION_START = "CoreService.START"
        private const val ACTION_STOP = "CoreService.STOP"
        private const val ACTION_INCOMING_PSTN = "CoreService.INCOMING_PSTN"
        private const val ACTION_STOP_RING = "CoreService.STOP_RING"
        private const val ACTION_TEST_INVITE = "CoreService.TEST_INVITE"
        private const val ACTION_FORWARD_SMS = "CoreService.FORWARD_SMS"
        

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

        fun forwardSmsToPeer(context: Context, msgId: String, address: String, body: String, ts: Long) {
            val i = Intent(context, CoreService::class.java).setAction(ACTION_FORWARD_SMS)
            i.putExtra("msgId", msgId)
            i.putExtra("address", address)
            i.putExtra("body", body)
            i.putExtra("ts", ts)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var nm: NotificationManager
    private var socket: DatagramSocket? = null
    @Volatile private var running = true

    private var multicastLock: WifiManager.MulticastLock? = null

    // ✅ 关键：每个 callId 对应的本端音频端口，避免多次 INVITE 覆盖 myAudioPort
    private val audioPortByCallId = ConcurrentHashMap<String, Int>()
    private val acceptedCallIds = ConcurrentHashMap<String, Boolean>()

    @Volatile private var peerIp: InetAddress? = null
    @Volatile private var peerControlPort: Int = AppConfig.CONTROL_PORT

    @Volatile private var myAudioPort: Int = 0
    @Volatile private var callId: String? = null

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    private var lastPongLogTs: Long = 0
    private var lastPingLogTs: Long = 0
    private var lastHelloLogTs: Long = 0

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        vibrator = getSystemVibrator()
        createChannels()

        startForeground(AppConfig.NID_PERSIST, buildPersistNotification())

        AppLog.i(this, "CoreService 启动")
        startNetworking()

        if (isHasSimReady()) applyManualPairIfEnabled()
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
                // ✅ 不要在这里写 audioPortByCallId（cid 在 handleInviteSend 里才生成）
            }

            ACTION_TEST_INVITE -> {
                AppLog.i(this, "手动触发：发送测试 INVITE")
                handleInviteSend(number = "测试来电", isTest = true)
            }

            ACTION_FORWARD_SMS -> {
                val msgId = intent.getStringExtra("msgId") ?: return START_STICKY
                val address = intent.getStringExtra("address") ?: "未知号码"
                val body = intent.getStringExtra("body") ?: ""
                val ts = intent.getLongExtra("ts", System.currentTimeMillis())
                handleForwardSms(msgId, address, body, ts)
            }
        }
        return START_STICKY
    }

    private fun isHasSimReady(): Boolean {
        val tm = getSystemService(TelephonyManager::class.java)
        return tm?.simState == TelephonyManager.SIM_STATE_READY
    }

    // ===== PSTN 控制 =====
    private fun hasAnswerPerm(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
            } else true
        } catch (_: Throwable) {
            false
        }
    }

     private fun pstnAnswerIfPossible() {
       if (Build.VERSION.SDK_INT < 26) { AppLog.i(this,"PSTN：API<26"); return }
       if (!hasAnswerPerm()) { AppLog.i(this,"PSTN：无 ANSWER_PHONE_CALLS 权限"); return }
       if (!isHasSimReady()) return
       if (Build.VERSION.SDK_INT < 26) return
       if (!hasAnswerPerm()) return

       // ✅ 关键：必须真的在响铃才接听，避免测试INVITE干扰音频路由
      val tel = getSystemService(TelephonyManager::class.java)
      if (tel?.callState != TelephonyManager.CALL_STATE_RINGING) {
          AppLog.i(this, "PSTN：当前不是响铃状态，跳过 acceptRingingCall()")
          return
      }

      try {
          val tm = getSystemService(TelecomManager::class.java) ?: return
          runCatching { tm.silenceRinger() }
          tm.acceptRingingCall()
          AppLog.i(this, "PSTN：已调用 acceptRingingCall()")
      } catch (t: Throwable) {
          AppLog.i(this, "PSTN：接听失败：${t.javaClass.simpleName} ${t.message}")
      }
    }

    private fun pstnEndIfPossible(reason: String) {
    if (!isHasSimReady()) return
    if (Build.VERSION.SDK_INT < 28) {
        AppLog.i(this, "PSTN：API<28，无法 endCall()")
        return
    }
    if (!hasAnswerPerm()) {
        AppLog.i(this, "PSTN：缺少 ANSWER_PHONE_CALLS，无法挂断（请在有卡机一键授权）")
        return
    }

    // ✅ 关键：只有电话不空闲时才尝试挂断，避免测试时 ok=false 干扰日志
    val tel = getSystemService(TelephonyManager::class.java)
    if (tel?.callState == TelephonyManager.CALL_STATE_IDLE) {
        AppLog.i(this, "PSTN：当前通话状态=IDLE，跳过 endCall() reason=$reason")
        return
    }

    try {
        val tm = getSystemService(TelecomManager::class.java) ?: run {
            AppLog.i(this, "PSTN：TelecomManager=null")
            return
        }
        val ok = tm.endCall()
        AppLog.i(this, "PSTN：已调用 endCall() reason=$reason ok=$ok")
    } catch (t: Throwable) {
        AppLog.i(this, "PSTN：挂断失败：${t.javaClass.simpleName} ${t.message}")
    }
}
    // =====================

    private fun startNetworking() {
        socket = DatagramSocket(AppConfig.CONTROL_PORT).apply { broadcast = true }

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
            if (!Prefs.isManualPairEnabled(this)) {
                acquireMulticast()
                startHelloBroadcastSmart()
                AppLog.i(this, "接听端：开始智能广播 HELLO（未连接才发，连接后停发）")
            } else {
                AppLog.i(this, "接听端：已开启手动配对，跳过 HELLO 广播")
            }
        } else {
            AppLog.i(this, "有卡端：启动自适应 PING（未连=2s，已连=30s）")
            startPingLoopAdaptive()
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

    private fun startPingLoopAdaptive() {
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

                val interval = if (ConnectionState.isConnected(this)) 30_000L else 2_000L
                try { Thread.sleep(interval) } catch (_: Throwable) {}
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

    private fun startHelloBroadcastSmart() {
        thread(name = "hello-bcast") {
            while (running) {
                try {
                    if (ConnectionState.isConnected(this@CoreService)) {
                        try { Thread.sleep(30_000) } catch (_: Throwable) {}
                        continue
                    }

                    val bcast = NetUtils.getBroadcastAddress(this@CoreService)
                        ?: InetAddress.getByName("255.255.255.255")
                    val obj = JSONObject().put("type", "HELLO").put("controlPort", AppConfig.CONTROL_PORT)
                    val data = obj.toString().toByteArray(Charsets.UTF_8)
                    socket?.send(DatagramPacket(data, data.size, bcast, AppConfig.CONTROL_PORT))

                    val now = System.currentTimeMillis()
                    if (now - lastHelloLogTs > 20_000) {
                        lastHelloLogTs = now
                        AppLog.i(this, "接听端：广播 HELLO -> ${bcast.hostAddress}")
                    }
                } catch (_: Throwable) {}

                try { Thread.sleep(AppConfig.HELLO_INTERVAL_MS) } catch (_: Throwable) {}
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
            }

            "INVITE" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)

                val number = obj.optString("number", "未知号码")
                val cid = obj.optString("callId", UUID.randomUUID().toString())
                val senderAudioPort = obj.optInt("audioPort", 0)
                val senderCtrlPort = obj.optInt("controlPort", AppConfig.CONTROL_PORT)
                val isTest = obj.optBoolean("test", false)

                val ack = JSONObject().put("type", "INVITE_ACK").put("callId", cid).put("test", isTest)
                sendJson(fromIp, fromPort, ack)
                sendJson(fromIp, senderCtrlPort, ack)

                val current = CallState.incoming
                if (current?.callId == cid) return

                CallState.incoming = PendingInvite(
                    callId = cid,
                    number = number,
                    peerIp = fromIp.hostAddress,
                    peerControlPort = senderCtrlPort,
                    peerAudioPort = senderAudioPort
                )

                // 记录通话记录（IN）
                HistoryStore.upsertCall(this, cid, "IN", number, fromIp.hostAddress, isTest, "RINGING")

                AppLog.i(this, "接听端：收到 INVITE number=$number test=$isTest from=$fromIp")

                stopRinging()
                ringAndShowIncoming(number)
            }

            "INVITE_ACK" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                AppLog.i(this, "有卡端：收到 INVITE_ACK <- ${fromIp.hostAddress} callId=${obj.optString("callId")}")
            }

            "ACCEPT" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val cid = obj.optString("callId", "")
                val peerAudioPort = obj.optInt("audioPort", 0)

                if (cid.isNotBlank() && acceptedCallIds.putIfAbsent(cid, true) != null) {
                    AppLog.i(this, "有卡端：收到重复 ACCEPT，已忽略 callId=$cid")
                    return
                }

                AppLog.i(this, "有卡端：收到 ACCEPT <- ${fromIp.hostAddress} callId=$cid peerAudioPort=$peerAudioPort")

                if (cid.isNotBlank()) {
                    HistoryStore.updateCallState(this, cid, "ANSWERED")
                }

                // 关键：真正接起运营商电话
                pstnAnswerIfPossible()

                val localPort = audioPortByCallId[cid] ?: myAudioPort
                if (peerAudioPort > 0 && localPort > 0) {
                    AudioCallService.start(this, fromIp.hostAddress, peerAudioPort, localPort)
                    AppLog.i(this, "有卡端：已启动 AudioCallService localPort=$localPort peerAudioPort=$peerAudioPort")
                }
            }

            "DECLINE", "HANGUP" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val cid = obj.optString("callId", "")

                if (cid.isNotBlank()) {
                    acceptedCallIds.remove(cid)
                    audioPortByCallId.remove(cid)
                    val newState = if (obj.optString("type") == "HANGUP") "ENDED" else "DECLINED"
                    HistoryStore.updateCallState(this, cid, newState)
                }

                AppLog.i(this, "收到 ${obj.optString("type")} <- ${fromIp.hostAddress} callId=$cid，停止对讲 + 挂断PSTN")

                pstnEndIfPossible(obj.optString("type"))

                try { AudioCallService.stop(this) } catch (_: Throwable) {}
                currentCallId = null
            }

            "SMS" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val msgId = obj.optString("msgId", UUID.randomUUID().toString())
                val address = obj.optString("address", "未知号码")
                val body = obj.optString("body", "")
                val ts = obj.optLong("ts", System.currentTimeMillis())

                HistoryStore.insertSmsIn(this, msgId, address, body, fromIp.hostAddress, ts)
                showSmsNotification(address, body)

                AppLog.i(this, "收到 SMS <- ${fromIp.hostAddress} from=$address")
            }
        }
    }

            "DECLINE", "HANGUP" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val cid = obj.optString("callId", "")

                if (cid.isNotBlank()) {
                    acceptedCallIds.remove(cid)
                    audioPortByCallId.remove(cid)
                    HistoryStore.updateCallState(this, cid, if (obj.optString("type") == "HANGUP") "ENDED" else "DECLINED")
                }

                AppLog.i(this, "收到 ${obj.optString("type")} <- ${fromIp.hostAddress} callId=$cid，停止对讲 + 挂断PSTN")

                pstnEndIfPossible(obj.optString("type"))

                try { AudioCallService.stop(this) } catch (_: Throwable) {}
                currentCallId = null
            }

            "SMS" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val msgId = obj.optString("msgId", UUID.randomUUID().toString())
                val address = obj.optString("address", "未知号码")
                val body = obj.optString("body", "")
                val ts = obj.optLong("ts", System.currentTimeMillis())

                HistoryStore.insertSmsIn(this, msgId, address, body, fromIp.hostAddress, ts)
                showSmsNotification(address, body)
            }
        }
    }
             "DECLINE" -> {
                 Prefs.markPeerSeen(this, fromIp.hostAddress)
                 val cid = obj.optString("callId", "")

                 if (cid.isNotBlank()) {
                     acceptedCallIds.remove(cid)
                     HistoryStore.updateCallState(this, cid, "DECLINED")
                     audioPortByCallId.remove(cid)
                 }

                 AppLog.i(this, "有卡端：收到 DECLINE <- ${fromIp.hostAddress} callId=$cid")
                 pstnEndIfPossible("DECLINE")
           }
            "HANGUP" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val cid = obj.optString("callId", "")

                if (cid.isNotBlank()) {
                    acceptedCallIds.remove(cid)
                    HistoryStore.updateCallState(this, cid, "ENDED")
                    audioPortByCallId.remove(cid)
                }

                AppLog.i(this, "收到 HANGUP <- ${fromIp.hostAddress} callId=$cid，停止对讲 + 挂断PSTN")

                pstnEndIfPossible("HANGUP")

                try { AudioCallService.stop(this) } catch (_: Throwable) {}
                callId = null
                myAudioPort = 0
          }

            "SMS" -> {
                Prefs.markPeerSeen(this, fromIp.hostAddress)
                val msgId = obj.optString("msgId", UUID.randomUUID().toString())
                val address = obj.optString("address", "未知号码")
                val body = obj.optString("body", "")
                val ts = obj.optLong("ts", System.currentTimeMillis())

                HistoryStore.insertSmsIn(this, msgId, address, body, fromIp.hostAddress, ts)
                showSmsNotification(address, body)

                AppLog.i(this, "收到 SMS <- ${fromIp.hostAddress} from=$address len=${body.length}")
            }
        }
    }

    private fun sendJson(ip: InetAddress, port: Int, obj: JSONObject) {
        try {
            val data = obj.toString().toByteArray(Charsets.UTF_8)
            socket?.send(DatagramPacket(data, data.size, ip, port))
        } catch (_: Throwable) {}
    }

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

            // ✅ 关键：在这里写入映射（cid 在这里才生成）
            audioPortByCallId[cid] = myAudioPort

            HistoryStore.upsertCall(this@CoreService, cid, "OUT", number, peer.hostAddress, isTest, "RINGING")

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

            AppLog.i(this, "有卡端：发送 INVITE x3 -> ${peer.hostAddress} number=$number test=$isTest myAudioPort=$myAudioPort callId=$cid")
        }
    }

    private fun handleForwardSms(msgId: String, address: String, body: String, ts: Long) {
        if (!isHasSimReady()) return

        thread(name = "send-sms") {
            applyManualPairIfEnabled()
            val peer = peerIp
            if (peer == null) {
                AppLog.i(this@CoreService, "短信转发：未找到接听端（对端不在线/未配对）")
                return@thread
            }

            val obj = JSONObject()
                .put("type", "SMS")
                .put("msgId", msgId)
                .put("address", address)
                .put("body", body)
                .put("ts", ts)

            val delays = longArrayOf(0, 120, 300)
            for (d in delays) {
                try { Thread.sleep(d) } catch (_: Throwable) {}
                sendJson(peer, peerControlPort, obj)
            }

            AppLog.i(this@CoreService, "短信转发：已发送 x3 -> ${peer.hostAddress} from=$address len=${body.length}")
        }
    }

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

    private fun showSmsNotification(address: String, body: String) {
        try {
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, SmsBoxActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(this, AppConfig.CH_MSG)
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setContentTitle("新短信：$address")
                .setContentText(body.take(60))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(AppConfig.NID_MSG, n)
        } catch (_: Throwable) {}
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
        } catch (_: Throwable) { null }
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
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("LanCall")
            .setContentText("后台运行中")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(AppConfig.CH_PERSIST, "LanCall 常驻", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(AppConfig.CH_CALL, "LanCall 来电", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(AppConfig.CH_MSG, "LanCall 短信", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(AppConfig.CH_ONGOING, "LanCall 通话中", NotificationManager.IMPORTANCE_LOW))
    }
}
