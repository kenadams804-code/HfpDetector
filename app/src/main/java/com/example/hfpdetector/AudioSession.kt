package com.example.hfpdetector

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class AudioSession(
    private val context: Context,
    private val peerIp: String,
    private val peerAudioPort: Int,
    private val myAudioPort: Int
) {
    @Volatile private var running = false

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var sock: DatagramSocket? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile private var sentBytes: Long = 0
    @Volatile private var recvBytes: Long = 0
    @Volatile private var lastMicPeak: Int = 0

    fun start() {
        if (running) return
        running = true

        try {
            AppLog.i(context, "AudioSession：start peer=$peerIp:$peerAudioPort myPort=$myAudioPort")

            val am = context.getSystemService(AudioManager::class.java)

            runCatching {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true

                // ✅ 关键：确保麦克风不是静音状态（你现在 micPeak 很低，像是被静音）
                am.isMicrophoneMute = false
                AppLog.i(context, "AudioSession：isMicrophoneMute=${am.isMicrophoneMute}")

                // ✅ 不要拉满音量（拉满非常容易啸叫/嗡鸣）
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val target = (maxVol * 0.55f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)

                // Android 12+ 尽量锁到扬声器（你已验证可用）
                if (Build.VERSION.SDK_INT >= 31) {
                    val spk = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (spk != null) {
                        am.setCommunicationDevice(spk)
                        AppLog.i(context, "AudioSession：setCommunicationDevice -> SPEAKER OK")
                    }
                }
            }

            val sampleRate = 16000
            val inConfig = AudioFormat.CHANNEL_IN_MONO
            val outConfig = AudioFormat.CHANNEL_OUT_MONO
            val fmt = AudioFormat.ENCODING_PCM_16BIT

            val minIn = AudioRecord.getMinBufferSize(sampleRate, inConfig, fmt)
            val minOut = AudioTrack.getMinBufferSize(sampleRate, outConfig, fmt)

            val recBuf = max(minIn, 2048)
            val playBuf = max(minOut, 2048)

            // ✅ VOICE_COMMUNICATION 优先（配合 AEC/NS/AGC）
            audioRecord = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate, inConfig, fmt,
                    recBuf * 2
                )
            }.getOrNull()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate, inConfig, fmt,
                        recBuf * 2
                    )
                }.getOrNull()
            }

            val rec = audioRecord
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession：AudioRecord 初始化失败")
                stop(); return
            }

            // ✅ 尽量选内置麦克风，避免输入设备选错
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    val mics = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val mic = mics.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    if (mic != null) {
                        rec.preferredDevice = mic
                        AppLog.i(context, "AudioSession：AudioRecord preferredDevice -> BUILTIN_MIC OK")
                    }
                }
            }

            // ✅ 开系统音频效果：回声消除/降噪/自动增益（抑制嗡鸣、让人声更可用）
            val sid = rec.audioSessionId

            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：AEC enabled=${aec?.enabled}")
                } else AppLog.i(context, "AudioSession：AEC not available")
            }
            runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：NS enabled=${ns?.enabled}")
                } else AppLog.i(context, "AudioSession：NS not available")
            }
            runCatching {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：AGC enabled=${agc?.enabled}")
                } else AppLog.i(context, "AudioSession：AGC not available")
            }

            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val af = AudioFormat.Builder()
                .setEncoding(fmt)
                .setSampleRate(sampleRate)
                .setChannelMask(outConfig)
                .build()

            audioTrack = AudioTrack(
                attr, af,
                playBuf * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            val tr = audioTrack
            if (tr == null || tr.state != AudioTrack.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession：AudioTrack 初始化失败")
                stop(); return
            }

            // ✅ 尽量把播放绑到扬声器
            runCatching {
                val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val spk = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (spk != null) {
                    tr.preferredDevice = spk
                    AppLog.i(context, "AudioSession：AudioTrack preferredDevice -> SPEAKER OK")
                }
            }

            sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(myAudioPort))
            }

            runCatching { rec.startRecording() }.onFailure {
                AppLog.i(context, "AudioSession：startRecording 失败：${it.javaClass.simpleName} ${it.message}")
                stop(); return
            }

            runCatching {
                tr.play()
                if (Build.VERSION.SDK_INT >= 21) tr.setVolume(1.0f) else @Suppress("DEPRECATION") tr.setStereoVolume(1f, 1f)
            }.onFailure {
                AppLog.i(context, "AudioSession：AudioTrack play 失败：${it.javaClass.simpleName} ${it.message}")
                stop(); return
            }

            startStatsLoop()
            startSendLoop()
            startRecvLoop()

            AppLog.i(context, "AudioSession：已启动（send/recv/stats）")
        } catch (t: Throwable) {
            AppLog.i(context, "AudioSession：start 异常：${t.javaClass.simpleName} ${t.message}")
            stop()
        }
    }

    private fun startStatsLoop() {
        thread(name = "aud-stats") {
            var lastS = 0L
            var lastR = 0L
            while (running) {
                try { Thread.sleep(3000) } catch (_: Throwable) {}
                val s = sentBytes
                val r = recvBytes
                val ds = s - lastS
                val dr = r - lastR
                lastS = s
                lastR = r
                AppLog.i(context, "AudioSession：发送=${ds}B/3s 接收=${dr}B/3s micPeak=$lastMicPeak (totalS=$s totalR=$r)")
            }
        }
    }

    private fun startSendLoop() {
        thread(name = "aud-send") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val rec = audioRecord ?: return@thread
            val s = sock ?: return@thread
            val peerAddr = runCatching { InetAddress.getByName(peerIp) }.getOrNull() ?: return@thread
            val buf = ByteArray(640)

            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(0)
                if (n > 0) {
                    // 录音峰值（判断你说话时有没有采到）
                    var peak = 0
                    var i = 0
                    while (i + 1 < n) {
                        val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                        val sv = if (v > 32767) v - 65536 else v
                        peak = max(peak, abs(sv))
                        i += 2
                    }
                    lastMicPeak = peak

                    try {
                        s.send(DatagramPacket(buf, n, peerAddr, peerAudioPort))
                        sentBytes += n.toLong()
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun startRecvLoop() {
        thread(name = "aud-recv") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val tr = audioTrack ?: return@thread
            val s = sock ?: return@thread
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)

            while (running) {
                try {
                    p.length = buf.size
                    s.receive(p)
                    recvBytes += p.length.toLong()
                    tr.write(p.data, 0, p.length)
                } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        running = false

        try { sock?.close() } catch (_: Throwable) {}
        sock = null

        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null

        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null

        try { aec?.release() } catch (_: Throwable) {}
        try { ns?.release() } catch (_: Throwable) {}
        try { agc?.release() } catch (_: Throwable) {}
        aec = null; ns = null; agc = null
    }
}
