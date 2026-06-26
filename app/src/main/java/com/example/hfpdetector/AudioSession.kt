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
import java.net.PortUnreachableException
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

    // ==== audio params ====
    private val FRAME_MS = 20
    private val SAMPLE_RATE = 16000
    private val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 320
    private val FRAME_BYTES = FRAME_SAMPLES * 2               // 640

    // ==== jitter buffer ====
    private val qLock = Any()
    private val jitterQ = ArrayDeque<ByteArray>()

    // 建议先保守一点，减少断续
    private val JITTER_MAX = 20
    private val PREBUFFER = 5
    private val CATCHUP_TARGET = 12

    // ==== stats ====
    @Volatile private var sentBytes: Long = 0
    @Volatile private var recvBytes: Long = 0
    @Volatile private var lastMicPeak: Int = 0
    @Volatile private var jitterSize: Int = 0
    @Volatile private var underflow: Long = 0
    @Volatile private var dropped: Long = 0

    // effects
    private val ENABLE_AEC = true
    private val ENABLE_NS = false
    private val ENABLE_AGC = false

    // noise gate（先别太激进）
    private val NOISE_GATE = 120
    private val HANGOVER_FRAMES = 10
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
            }

            val inConfig = AudioFormat.CHANNEL_IN_MONO
            val outConfig = AudioFormat.CHANNEL_OUT_MONO
            val fmt = AudioFormat.ENCODING_PCM_16BIT

            val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, inConfig, fmt)
            val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, outConfig, fmt)
            val recBuf = max(minIn, 4096)
            val playBuf = max(minOut, 4096)

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
                stop(); return
            }

            // AEC/NS/AGC
            val sid = rec.audioSessionId
            runCatching {
                if (ENABLE_AEC && AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession AEC enabled=${aec?.enabled}")
                } else AppLog.i(context, "AudioSession AEC disabled/unavailable")
            }
            runCatching {
                if (ENABLE_NS && NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession NS enabled=${ns?.enabled}")
                } else AppLog.i(context, "AudioSession NS disabled/unavailable")
            }
            runCatching {
                if (ENABLE_AGC && AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession AGC enabled=${agc?.enabled}")
                } else AppLog.i(context, "AudioSession AGC disabled/unavailable")
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
                AppLog.i(context, "AudioSession AudioTrack init FAILED")
                stop(); return
            }

            // UDP socket（关键：不 connect，发送时显式指定 peerAddr，避免 null address NPE）
            sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 300
                bind(InetSocketAddress(myAudioPort))
                runCatching { receiveBufferSize = 1 shl 20 }
                runCatching { sendBufferSize = 1 shl 20 }
            }

            rec.startRecording()
            tr.play()

            startRxThread()
            startTxThread()
            startPlayoutThread()
            startStatsThread()

            AppLog.i(context, "AudioSession：已启动（rx/tx/play/stats）")

        } catch (t: Throwable) {
            AppLog.i(context, "AudioSession start exception: ${t.javaClass.simpleName} ${t.message}")
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
                    val from = pkt.address?.hostAddress ?: ""
                    if (from.isNotBlank() && from != peerIp) {
                        // 只收对端的（避免局域网杂包）
                        continue
                    }

                    val len = pkt.length
                    if (len <= 0) continue

                    val frame = ByteArray(FRAME_BYTES)
                    if (len >= FRAME_BYTES) {
                        System.arraycopy(buf, 0, frame, 0, FRAME_BYTES)
                    } else {
                        System.arraycopy(buf, 0, frame, 0, len)
                    }

                    recvBytes += minOf(len, FRAME_BYTES).toLong()

                    synchronized(qLock) {
                        while (jitterQ.size >= JITTER_MAX) {
                            jitterQ.removeFirst()
                            dropped++
                        }
                        jitterQ.addLast(frame)
                        jitterSize = jitterQ.size
                    }

                } catch (_: SocketTimeoutException) {
                    // allow exit
                } catch (_: PortUnreachableException) {
                    // ✅ 忽略：对端端口临时不可达/系统回报 ICMP，不要因此中断
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioRx exception: ${t.javaClass.simpleName} ${t.message}")
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

            // 复用 packet，避免频繁分配
            val pktVoice = DatagramPacket(outBytes, outBytes.size, peerAddr, peerAudioPort)
            val pktSilence = DatagramPacket(silenceFrame, silenceFrame.size, peerAddr, peerAudioPort)

            while (running) {
                // 读满一帧
                var got = 0
                while (running && got < FRAME_SAMPLES) {
                    val n = try {
                        if (Build.VERSION.SDK_INT >= 23) {
                            rec.read(shortBuf, got, FRAME_SAMPLES - got, AudioRecord.READ_BLOCKING)
                        } else {
                            rec.read(shortBuf, got, FRAME_SAMPLES - got)
                        }
                    } catch (t: Throwable) {
                        if (running) AppLog.i(context, "AudioTx read exception: ${t.javaClass.simpleName} ${t.message}")
                        -1
                    }
                    if (n <= 0) break
                    got += n
                }

                if (got <= 0) {
                    SystemClock.sleep(5)
                    continue
                }

                for (i in got until FRAME_SAMPLES) shortBuf[i] = 0

                // micPeak + meanAbs
                var peak = 0
                var sumAbs = 0
                for (i in 0 until FRAME_SAMPLES) {
                    val v = shortBuf[i].toInt()
                    val a = abs(v)
                    if (a > peak) peak = a
                    sumAbs += a
                }
                lastMicPeak = peak
                val meanAbs = sumAbs / FRAME_SAMPLES

                val sendSilence = if (meanAbs >= NOISE_GATE) {
                    hangover = HANGOVER_FRAMES
                    false
                } else {
                    if (hangover > 0) { hangover--; false } else true
                }

                try {
                    if (sendSilence) {
                        s.send(pktSilence)
                        sentBytes += silenceFrame.size.toLong()
                        continue
                    }

                    // Short -> LE bytes
                    var bi = 0
                    for (i in 0 until FRAME_SAMPLES) {
                        val v = shortBuf[i].toInt()
                        outBytes[bi++] = (v and 0xFF).toByte()
                        outBytes[bi++] = ((v shr 8) and 0xFF).toByte()
                    }

                    // pktVoice 的 data 就是 outBytes，无需 setData
                    s.send(pktVoice)
                    sentBytes += outBytes.size.toLong()

                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioTx send exception: ${t.javaClass.simpleName} ${t.message}")
                }
            }
        }
    }

    private fun startPlayoutThread() {
        thread(name = "AudioPlayout", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val tr = audioTrack ?: return@thread

            // 预缓冲
            val waitUntil = SystemClock.elapsedRealtime() + 800
            while (running) {
                val ok = synchronized(qLock) { jitterQ.size >= PREBUFFER }
                if (ok) break
                if (SystemClock.elapsedRealtime() > waitUntil) break
                SystemClock.sleep(5)
            }

            val t0 = SystemClock.elapsedRealtime()
            var frameIdx = 0L

            while (running) {
                val targetTs = t0 + frameIdx * FRAME_MS
                val now = SystemClock.elapsedRealtime()
                val sleepMs = targetTs - now
                if (sleepMs > 0) SystemClock.sleep(sleepMs)

                val frame: ByteArray? = synchronized(qLock) {
                    while (jitterQ.size > CATCHUP_TARGET) {
                        jitterQ.removeFirst()
                        dropped++
                    }
                    jitterSize = jitterQ.size
                    if (jitterQ.isNotEmpty()) jitterQ.removeFirst() else null
                }

                val toPlay = if (frame != null) frame else {
                    underflow++
                    silenceFrame
                }

                try {
                    tr.write(toPlay, 0, toPlay.size)
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioPlayout write exception: ${t.javaClass.simpleName} ${t.message}")
                }

                frameIdx++
            }
        }
    }

    private fun startStatsThread() {
        thread(name = "AudioStats", isDaemon = true) {
            var lastSent = 0L
            var lastRecv = 0L
            var lastUnder = 0L
            var lastDrop = 0L

            while (running) {
                SystemClock.sleep(1000)

                val s = sentBytes
                val r = recvBytes
                val u = underflow
                val d = dropped

                val ds = s - lastSent
                val dr = r - lastRecv
                val du = u - lastUnder
                val dd = d - lastDrop

                lastSent = s
                lastRecv = r
                lastUnder = u
                lastDrop = d

                AppLog.i(
                    context,
                    "Audio stats: jitterQ=$jitterSize underflow=+$du drop=+$dd micPeak=$lastMicPeak tx=${ds}B/s rx=${dr}B/s"
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
