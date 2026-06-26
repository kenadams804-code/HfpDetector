package com.example.hfpdetector

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
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

    // ==== stats ====
    @Volatile private var sentBytes: Long = 0
    @Volatile private var recvBytes: Long = 0
    @Volatile private var lastMicPeak: Int = 0
    @Volatile private var jitterSize: Int = 0

    // ==== jitter buffer ====
    private val qLock = Any()
    private val jitterQ = ArrayDeque<ByteArray>()

    private val SAMPLE_RATE = 16000
    private val FRAME_BYTES = 640 // 20ms @16kHz mono 16bit
    private val JITTER_MAX = 10   // ≈200ms
    private val PREBUFFER = 3     // ≈60ms

    // ✅ 减少“风声/水声”伪影：默认只开 AEC，先关 NS/AGC（你后面想再开我再帮你做开关）
    private val ENABLE_AEC = true
    private val ENABLE_NS = false
    private val ENABLE_AGC = false

    fun start() {
        if (running) return
        running = true

        try {
            AppLog.i(context, "AudioSession：start peer=$peerIp:$peerAudioPort myPort=$myAudioPort")

            val am = context.getSystemService(AudioManager::class.java)

            runCatching {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                am.isMicrophoneMute = false
                AppLog.i(context, "AudioSession：isMicrophoneMute=${am.isMicrophoneMute}")

                // 音量别太大（否则回授+不适）
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val target = (maxVol * 0.45f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)

                if (Build.VERSION.SDK_INT >= 31) {
                    val spk = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (spk != null) {
                        am.setCommunicationDevice(spk)
                        AppLog.i(context, "AudioSession：setCommunicationDevice -> SPEAKER OK")
                    }
                }
            }

            val inConfig = AudioFormat.CHANNEL_IN_MONO
            val outConfig = AudioFormat.CHANNEL_OUT_MONO
            val fmt = AudioFormat.ENCODING_PCM_16BIT

            val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, inConfig, fmt)
            val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, outConfig, fmt)

            val recBuf = max(minIn, 2048)
            val playBuf = max(minOut, 2048)

            audioRecord = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, inConfig, fmt, recBuf * 2)
            }.getOrNull()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, inConfig, fmt, recBuf * 2)
                }.getOrNull()
            }

            val rec = audioRecord
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession：AudioRecord 初始化失败")
                stop(); return
            }

            // 选内置麦克风
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

            val sid = rec.audioSessionId
            runCatching {
                if (ENABLE_AEC && AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：AEC enabled=${aec?.enabled}")
                } else AppLog.i(context, "AudioSession：AEC disabled/unavailable")
            }
            runCatching {
                if (ENABLE_NS && NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：NS enabled=${ns?.enabled}")
                } else AppLog.i(context, "AudioSession：NS disabled/unavailable")
            }
            runCatching {
                if (ENABLE_AGC && AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：AGC enabled=${agc?.enabled}")
                } else AppLog.i(context, "AudioSession：AGC disabled/unavailable")
            }

            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val af = AudioFormat.Builder()
                .setEncoding(fmt)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(outConfig)
                .build()

            audioTrack = AudioTrack(attr, af, playBuf * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            val tr = audioTrack
            if (tr == null || tr.state != AudioTrack.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession：AudioTrack 初始化失败")
                stop(); return
            }

            // 尽量绑扬声器
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
                receiveBufferSize = 1024 * 1024
                sendBufferSize = 1024 * 1024
                bind(InetSocketAddress(myAudioPort))
            }

            runCatching { rec.startRecording() }.onFailure {
                AppLog.i(context, "AudioSession：startRecording 失败：${it.javaClass.simpleName} ${it.message}")
                stop(); return
            }

            runCatching { tr.play() }.onFailure {
                AppLog.i(context, "AudioSession：AudioTrack play 失败：${it.javaClass.simpleName} ${it.message}")
                stop(); return
            }

            startStatsLoop()
            startSendLoop()
            startRecvEnqueueLoop()
            startPlayLoopBlocking()

            AppLog.i(context, "AudioSession：已启动（send/recvEnqueue/playBlocking/stats）")
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
                AppLog.i(context, "AudioSession：发送=${ds}B/3s 接收=${dr}B/3s micPeak=$lastMicPeak jitterQ=$jitterSize
