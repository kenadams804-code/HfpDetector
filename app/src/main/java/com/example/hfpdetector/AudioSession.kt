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

    private val FRAME_MS = 20
    private val SAMPLE_RATE = 16000
    private val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 320
    private val FRAME_BYTES = FRAME_SAMPLES * 2               // 640

    // jitter - 优化后参数
    private val qLock = Any()
    private val jitterQ = ArrayDeque<ByteArray>()
    private val JITTER_MAX = 25
    private val PREBUFFER = 10          // 增加预缓冲，减少断续
    private val CATCHUP_TARGET = 15

    // stats
    @Volatile private var sentBytes: Long = 0
    @Volatile private var recvBytes: Long = 0
    @Volatile private var jitterSize: Int = 0
    @Volatile private var lastMicPeak: Int = 0
    @Volatile private var lastRxPeak: Int = 0
    @Volatile private var underflow: Long = 0
    @Volatile private var dropped: Long = 0

    // effects
    private val ENABLE_AEC = true
    private val ENABLE_NS = false
    private val ENABLE_AGC = false

    private val ENABLE_NOISE_GATE = false
    private val silenceFrame = ByteArray(FRAME_BYTES)

    // 增益调节
    private val MIC_GAIN = 5.0f      // 麦克风增益
    private val RX_GAIN = 1.8f       // 播放端增益（提升整体响度）

    // audio focus
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocused = false

    fun start() {
        if (running) return
        running = true

        try {
            AppLog.i(context, "AudioSession start peer=$peerIp:$peerAudioPort myPort=$myAudioPort MIC_GAIN=$MIC_GAIN RX_GAIN=$RX_GAIN")

            val am = context.getSystemService(AudioManager::class.java)

            requestFocus(am)

            runCatching {
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val target = (maxVol * 0.9f).toInt().coerceAtLeast(8)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
                AppLog.i(context, "AudioSession voiceCallVol=${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/$maxVol")
            }

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
            val recBuf = max(minIn, 8192)
            val playBuf = max(minOut, 8192)

            audioRecord = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, inConfig, fmt, recBuf * 2)
            }.getOrNull() ?: runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, inConfig, fmt, recBuf * 2)
            }.getOrNull()

            val rec = audioRecord
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession AudioRecord init FAILED")
                stop(); return
            }

            val sid = rec.audioSessionId
            runCatching { if (ENABLE_AEC && AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sid)?.apply { enabled = true } }
            runCatching { if (ENABLE_NS && NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sid)?.apply { enabled = true } }
            runCatching { if (ENABLE_AGC && AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sid)?.apply { enabled = true } }

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
                AppLog.i(context, "AudioSession AudioTrack init FAILED")
                stop(); return
            }
            runCatching { tr.setVolume(1.0f) }

            sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 200
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

            AppLog.i(context, "AudioSession started (MIC_GAIN=$MIC_GAIN RX_GAIN=$RX_GAIN)")

        } catch (t: Throwable) {
            AppLog.i(context, "AudioSession start exception: ${t.javaClass.simpleName} ${t.message}")
            stop()
        }
    }

    private fun requestFocus(am: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                audioFocused = (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            } else {
                @Suppress("DEPRECATION")
                audioFocused = (am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
            AppLog.i(context, "AudioSession audioFocus granted=$audioFocused")
        } catch (t: Throwable) {
            AppLog.i(context, "AudioSession requestFocus err")
        }
    }

    private fun abandonFocus() {
        try {
            val am = context.getSystemService(AudioManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
        audioFocusRequest = null
        audioFocused = false
    }

    private fun startRxThread() { /* 保持不变 */ 
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
                    System.arraycopy(buf, 0, frame, 0, minOf(len, FRAME_BYTES))

                    recvBytes += minOf(len, FRAME_BYTES).toLong()

                    synchronized(qLock) {
                        while (jitterQ.size >= JITTER_MAX) {
                            jitterQ.removeFirst()
                            dropped++
                        }
                        jitterQ.addLast(frame)
                        jitterSize = jitterQ.size
                    }
                } catch (_: SocketTimeoutException) {}
                catch (_: PortUnreachableException) {}
                catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioRx exception: ${t.javaClass.simpleName}")
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
            val pktVoice = DatagramPacket(outBytes, outBytes.size, peerAddr, peerAudioPort)

            while (running) {
                var got = 0
                while (running && got < FRAME_SAMPLES) {
                    val n = if (Build.VERSION.SDK_INT >= 23) {
                        rec.read(shortBuf, got, FRAME_SAMPLES - got, AudioRecord.READ_BLOCKING)
                    } else {
                        rec.read(shortBuf, got, FRAME_SAMPLES - got)
                    }
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) {
                    SystemClock.sleep(5)
                    continue
                }
                for (i in got until FRAME_SAMPLES) shortBuf[i] = 0

                var peak = 0
                for (i in 0 until FRAME_SAMPLES) {
                    val a = abs(shortBuf[i].toInt())
                    if (a > peak) peak = a
                }
                lastMicPeak = peak

                // 麦克风增益
                for (i in 0 until FRAME_SAMPLES) {
                    val v = (shortBuf[i] * MIC_GAIN).toInt()
                    shortBuf[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                var bi = 0
                for (i in 0 until FRAME_SAMPLES) {
                    val v = shortBuf[i].toInt()
                    outBytes[bi++] = (v and 0xFF).toByte()
                    outBytes[bi++] = ((v shr 8) and 0xFF).toByte()
                }

                try {
                    s.send(pktVoice)
                    sentBytes += outBytes.size.toLong()
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioTx send exception")
                }
            }
        }
    }

    private fun startPlayoutThread() {
        thread(name = "AudioPlayout", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val tr = audioTrack ?: return@thread

            // 等待预缓冲
            val waitUntil = SystemClock.elapsedRealtime() + 1200
            while (running) {
                if (synchronized(qLock) { jitterQ.size >= PREBUFFER }) break
                if (SystemClock.elapsedRealtime() > waitUntil) break
                SystemClock.sleep(5)
            }

            val t0 = SystemClock.elapsedRealtime()
            var frameIdx = 0L

            while (running) {
                val targetTs = t0 + frameIdx * FRAME_MS
                val sleepMs = targetTs - SystemClock.elapsedRealtime()
                if (sleepMs > 0) SystemClock.sleep(sleepMs)

                val frame: ByteArray? = synchronized(qLock) {
                    while (jitterQ.size > CATCHUP_TARGET) {
                        jitterQ.removeFirst()
                        dropped++
                    }
                    jitterSize = jitterQ.size
                    if (jitterQ.isNotEmpty()) jitterQ.removeFirst() else null
                }

                val rawFrame = frame ?: run { underflow++; silenceFrame }

                // RX 增益 + 计算 peak
                val toPlay = applyRxGain(rawFrame)
                lastRxPeak = pcm16lePeak(toPlay)

                try {
                    tr.write(toPlay, 0, toPlay.size)
                } catch (t: Throwable) {
                    if (running) AppLog.i(context, "AudioPlayout write exception")
                }

                frameIdx++
            }
        }
    }

    private fun applyRxGain(buf: ByteArray): ByteArray {
        if (RX_GAIN == 1.0f) return buf
        val out = ByteArray(FRAME_BYTES)
        var i = 0
        while (i + 1 < buf.size) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            var v = (hi shl 8) or lo
            if (v and 0x8000 != 0) v = v or -0x10000
            v = (v * RX_GAIN).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun pcm16lePeak(buf: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < buf.size) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            var v = (hi shl 8) or lo
            if (v and 0x8000 != 0) v = v or -0x10000
            val a = abs(v)
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }

    private fun startStatsThread() {
        thread(name = "AudioStats", isDaemon = true) {
            while (running) {
                SystemClock.sleep(3000)
                if (!running) break
                AppLog.i(context, "Audio stats: jitterQ=$jitterSize underflow=+$underflow drop=+$dropped micPeak=$lastMicPeak rxPeak=$lastRxPeak tx=${sentBytes/3}B/s rx=${recvBytes/3}B/s")
                sentBytes = 0
                recvBytes = 0
            }
        }
    }

    fun stop() {
        running = false
        abandonFocus()
        audioRecord?.stop(); audioRecord?.release()
        audioTrack?.stop(); audioTrack?.release()
        sock?.close()
        aec?.release(); ns?.release(); agc?.release()
        AppLog.i(context, "AudioSession stopped")
    }
}
