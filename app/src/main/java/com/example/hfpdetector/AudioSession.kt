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

    private val FRAME_MS = 20
    private val SAMPLE_RATE = 16000
    private val FRAME_BYTES = 640 // 20ms @16kHz mono 16-bit => 320 samples => 640 bytes

    private val JITTER_MAX = 16   // 最大缓存帧数（16帧≈320ms）
    private val PREBUFFER = 5     // 预缓冲帧数（5帧≈100ms）

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

                // 不拉满音量（拉满更易啸叫/不适）
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val target = (maxVol * 0.55f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)

                // Android 12+ 锁到扬声器
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

            // 录音：VOICE_COMMUNICATION 优先，失败降级 MIC
            audioRecord = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, inConfig, fmt,
                    recBuf * 2
                )
            }.getOrNull()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, inConfig, fmt,
                        recBuf * 2
                    )
                }.getOrNull()
            }

            val rec = audioRecord
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession：AudioRecord 初始化失败")
                stop(); return
            }

            // 尽量选内置麦克风
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

            // AEC/NS/AGC（减少嗡鸣/回授、让语音更可用）
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
                .setSampleRate(SAMPLE_RATE)
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

            // 尽量把播放绑到扬声器
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
            startPlayLoop()

            AppLog.i(context, "AudioSession：已启动（send/recvEnqueue/play/stats）")
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
                AppLog.i(context, "AudioSession：发送=${ds}B/3s 接收=${dr}B/3s micPeak=$lastMicPeak jitterQ=$jitterSize")
            }
        }
    }

    private fun startSendLoop() {
        thread(name = "aud-send") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val rec = audioRecord ?: return@thread
            val s = sock ?: return@thread
            val peerAddr = runCatching { InetAddress.getByName(peerIp) }.getOrNull() ?: return@thread
            val buf = ByteArray(FRAME_BYTES)

            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(0)
                if (n > 0) {
                    // micPeak（判断是否采到人声）
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

    // 接收：收包并入队（不直接写 AudioTrack）
    private fun startRecvEnqueueLoop() {
        thread(name = "aud-recv") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val s = sock ?: return@thread
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)

            while (running) {
                try {
                    p.length = buf.size
                    s.receive(p)
                    recvBytes += p.length.toLong()

                    // 统一成固定帧大小，避免播放端时长漂移
                    val frame = ByteArray(FRAME_BYTES)
                    val copyLen = minOf(p.length, FRAME_BYTES)
                    System.arraycopy(p.data, 0, frame, 0, copyLen)

                    synchronized(qLock) {
                        if (jitterQ.size >= JITTER_MAX) jitterQ.removeFirst()
                        jitterQ.addLast(frame)
                        jitterSize = jitterQ.size
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    // 播放：固定节奏（20ms）取帧播放，缓解抖动/丢包卡顿
    private fun startPlayLoop() {
        thread(name = "aud-play") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val tr = audioTrack ?: return@thread
            val silence = ByteArray(FRAME_BYTES)

            // 预缓冲
            var waited = 0
            while (running) {
                val size = synchronized(qLock) { jitterQ.size.also { jitterSize = it } }
                if (size >= PREBUFFER || waited >= 600) break
                try { Thread.sleep(FRAME_MS.toLong()) } catch (_: Throwable) {}
                waited += FRAME_MS
            }

            while (running) {
                val frame: ByteArray? = synchronized(qLock) {
                    if (jitterQ.isEmpty()) null else jitterQ.removeFirst().also { jitterSize = jitterQ.size }
                }

                try {
                    if (frame != null) tr.write(frame, 0, frame.size)
                    else tr.write(silence, 0, silence.size)
                } catch (_: Throwable) {}

                try { Thread.sleep(FRAME_MS.toLong()) } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        running = false

        try { sock?.close() } catch (_: Throwable) {}
        sock = null

        synchronized(qLock) {
            jitterQ.clear()
            jitterSize = 0
        }

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
