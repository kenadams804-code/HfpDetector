package com.example.hfpdetector

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
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
    private val FRAME_BYTES = 640 // 20ms @16kHz mono 16bit => 16000*0.02*2 = 640
    private val FRAME_SAMPLES = FRAME_BYTES / 2

    private val JITTER_MAX = 10        // 最大缓存帧数（≈200ms）
    private val PREBUFFER = 3          // 预缓冲帧数（≈60ms）
    private val CATCHUP_TARGET = 5     // 超过就丢帧追赶，避免延迟越积越大

    // 只开 AEC，先关 NS/AGC（后续可做成设置项）
    private val ENABLE_AEC = true
    private val ENABLE_NS = false
    private val ENABLE_AGC = false

    // Noise gate：低于门限则发送静音帧；hangover 防止断断续续
    private val NOISE_GATE = 220           // 可在 120~400 之间调
    private val HANGOVER_FRAMES = 10       // 200ms
    private var hangover = 0

    private val silenceFrame = ByteArray(FRAME_BYTES)

    fun start() {
        if (running) return
        running = true

        try {
            AppLog.i(context, "AudioSession start peer=$peerIp:$peerAudioPort myPort=$myAudioPort")

            val am = context.getSystemService(AudioManager::class.java)

            runCatching {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                am.isMicrophoneMute = false

                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val target = (maxVol * 0.40f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)

                if (Build.VERSION.SDK_INT >= 31) {
                    val spk = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (spk != null) {
                        am.setCommunicationDevice(spk)
                        AppLog.i(context, "AudioSession setCommunicationDevice -> SPEAKER OK")
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

            // AudioRecord：VOICE_COMMUNICATION 优先，失败降级 MIC
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
                AppLog.i(context, "AudioSession AudioRecord init FAILED")
                stop()
                return
            }

            // 尽量选内置麦克风
            runCatching {
                if (Build.VERSION.SDK_INT >= 23) {
                    val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val mic = ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    if (mic != null) {
                        rec.preferredDevice = mic
                        AppLog.i(context, "AudioSession AudioRecord preferredDevice -> BUILTIN_MIC OK")
                    }
                }
            }

            // AEC / NS / AGC
            val sid = rec.audioSessionId

            runCatching {
                if (ENABLE_AEC && AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession AEC enabled=${aec?.enabled}")
                } else {
                    AppLog.i(context, "AudioSession AEC disabled/unavailable")
                }
            }

            runCatching {
                if (ENABLE_NS && NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession NS enabled=${ns?.enabled}")
                } else {
                    AppLog.i(context, "AudioSession NS disabled/unavailable")
                }
            }

            runCatching {
                if (ENABLE_AGC && AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession AGC enabled=${agc?.enabled}")
                } else {
                    AppLog.i(context, "AudioSession AGC disabled/unavailable")
                }
            }

            // AudioTrack
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
                AppLog.i(context, "AudioSession AudioTrack init FAILED")
                stop()
                return
            }

            // 尽量把播放绑到扬声器（旧 API）
            runCatching {
                if (Build.VERSION.SDK_INT >= 23) {
                    val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val spk = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (spk != null) {
                        tr.preferredDevice = spk
                        AppLog.i(context, "AudioSession AudioTrack preferredDevice -> SPEAKER OK")
                    }
                }
            }

            // UDP socket
            sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 500
                bind(InetSocketAddress(myAudioPort))
            }

            rec.startRecording()
            tr.play()

            startRxThread()
            startTxThread()
            startPlayoutThread()
            startStatsThread()

        } catch (t: Throwable) {
            AppLog.i(context, "AudioSession start exception: ${t.message}")
            stop()
        }
    }

    private fun startRxThread() {
        val s = sock ?: return
        thread(name = "AudioRx", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)

            while (running) {
                try {
                    s.receive(pkt)
                    val len = pkt.length
                    if (len <= 0) continue

                    val frame = ByteArray(FRAME_BYTES)
                    if (len >= FRAME_BYTES) {
                        System.arraycopy(buf, 0, frame, 0, FRAME_BYTES)
                    } else {
                        System.arraycopy(buf, 0, frame, 0, len)
                        // 剩余自动为 0
                    }

                    recvBytes += minOf(len, FRAME_BYTES).toLong()

                    synchronized(qLock) {
                        // 队列太大：丢掉最老的帧，避免越积越大
                        while (jitterQ.size >= JITTER_MAX) {
                            jitterQ.removeFirst()
                        }
                        jitterQ.addLast(frame)
                        jitterSize = jitterQ.size
                    }
                } catch (_: SocketTimeoutException) {
                    // 允许超时以便及时退出
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioRx exception: ${t.message}")
                }
            }
        }
    }

    private fun startTxThread() {
        val s = sock ?: return
        val peerAddr = InetAddress.getByName(peerIp)

        thread(name = "AudioTx", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val rec = audioRecord ?: return@thread
            val shortBuf = ShortArray(FRAME_SAMPLES)
            val outBytes = ByteArray(FRAME_BYTES)

            while (running) {
                val n = try {
                    rec.read(shortBuf, 0, shortBuf.size)
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioTx read exception: ${t.message}")
                    -1
                }

                if (n <= 0) {
                    // 录音异常时也不要狂打日志/狂发包
                    SystemClock.sleep(10)
                    continue
                }

                // micPeak
                var peak = 0
                for (i in 0 until n) {
                    val a = abs(shortBuf[i].toInt())
                    if (a > peak) peak = a
                }
                lastMicPeak = peak

                // Noise gate + hangover
                val sendSilence = if (peak >= NOISE_GATE) {
                    hangover = HANGOVER_FRAMES
                    false
                } else {
                    if (hangover > 0) {
                        hangover--
                        false
                    } else {
                        true
                    }
                }

                if (sendSilence) {
                    // 直接发静音帧（全 0）
                    val pkt = DatagramPacket(silenceFrame, silenceFrame.size, peerAddr, peerAudioPort)
                    try {
                        s.send(pkt)
                        sentBytes += silenceFrame.size.toLong()
                    } catch (t: Throwable) {
                        if (running) AppLog.i(context, "AudioTx send exception: ${t.message}")
                    }
                    continue
                }

                // ShortArray -> LittleEndian PCM16 bytes
                var bi = 0
                val toWrite = minOf(n, FRAME_SAMPLES)
                for (i in 0 until toWrite) {
                    val v = shortBuf[i].toInt()
                    outBytes[bi++] = (v and 0xFF).toByte()
                    outBytes[bi++] = ((v shr 8) and 0xFF).toByte()
                }
                // 不足部分补 0
                while (bi < FRAME_BYTES) outBytes[bi++] = 0

                val pkt = DatagramPacket(outBytes, outBytes.size, peerAddr, peerAudioPort)
                try {
                    s.send(pkt)
                    sentBytes += outBytes.size.toLong()
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioTx send exception: ${t.message}")
                }
            }
        }
    }

    private fun startPlayoutThread() {
        thread(name = "AudioPlayout", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val tr = audioTrack ?: return@thread

            // 先预缓冲一点点，减少“刚接通就卡顿/断续”
            val startWaitUntil = SystemClock.elapsedRealtime() + 600
            while (running) {
                val ok = synchronized(qLock) { jitterQ.size >= PREBUFFER }
                if (ok) break
                if (SystemClock.elapsedRealtime() > startWaitUntil) break
                SystemClock.sleep(5)
            }

            val t0 = SystemClock.elapsedRealtime()
            var frameIdx = 0L

            while (running) {
                // 按 20ms 时钟对齐播放，降低“越播越快/越慢”导致的漂移
                val targetTs = t0 + frameIdx * FRAME_MS
                val now = SystemClock.elapsedRealtime()
                val sleepMs = targetTs - now
                if (sleepMs > 0) SystemClock.sleep(sleepMs)

                val frame: ByteArray? = synchronized(qLock) {
                    // 队列过大：丢帧追赶，避免延迟堆积
                    while (jitterQ.size > CATCHUP_TARGET) {
                        jitterQ.removeFirst()
                    }
                    jitterSize = jitterQ.size
                    if (jitterQ.isNotEmpty()) jitterQ.removeFirst() else null
                }

                val toPlay = frame ?: silenceFrame
                try {
                    tr.write(toPlay, 0, toPlay.size)
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioPlayout write exception: ${t.message}")
                }

                frameIdx++
            }
        }
    }

    private fun startStatsThread() {
        thread(name = "AudioStats", isDaemon = true) {
            var lastSent = 0L
            var lastRecv = 0L
            while (running) {
                SystemClock.sleep(1000)
                val s = sentBytes
                val r = recvBytes
                val ds = s - lastSent
                val dr = r - lastRecv
                lastSent = s
                lastRecv = r

                AppLog.i(
                    context,
                    "Audio stats: jitterQ=$jitterSize micPeak=$lastMicPeak tx=${ds}B/s rx=${dr}B/s"
                )
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false

        AppLog.i(context, "AudioSession stop...")

        runCatching { sock?.close() }
        sock = null

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null

        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null
        ns = null
        agc = null

        synchronized(qLock) {
            jitterQ.clear()
            jitterSize = 0
        }

        AppLog.i(context, "AudioSession stopped")
    }
}
