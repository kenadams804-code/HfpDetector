package com.example.hfpdetector

import android.content.Context
import android.media.*
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class AudioSession(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000  // 320
        private const val FRAME_BYTES = FRAME_SAMPLES * 2

        private const val MIC_GAIN = 9.0f      // 进一步加大
        private const val RX_GAIN = 4.0f
        private const val PREBUFFER = 8
        private const val JITTER_MAX = 30
        private const val ENABLE_NOISE_GATE = false
        private const val NOISE_GATE = 180

        private val silenceFrame = ByteArray(FRAME_BYTES)  // 改为 ByteArray 更安全
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var txSocket: DatagramSocket? = null
    private var rxSocket: DatagramSocket? = null

    @Volatile private var running = false
    private var peerIp: InetAddress? = null
    private var peerPort = 0
    private var myPort = 0

    private val jitterBuffer = ArrayDeque<ByteArray>()
    private var lastRxPeak = 0

    private var txThread: Thread? = null
    private var rxThread: Thread? = null
    private var playThread: Thread? = null
    private var statsThread: Thread? = null

    fun start(peer: String, peerAudioPort: Int, localPort: Int) {
        try {
            peerIp = InetAddress.getByName(peer)
            peerPort = peerAudioPort
            myPort = localPort

            AppLog.i(this, "AudioSession start peer=$peer:$peerPort myPort=$myPort MIC_GAIN=$MIC_GAIN RX_GAIN=$RX_GAIN")

            setupSockets()
            setupAudioRecord()
            setupAudioTrack()

            running = true

            startTxThread()
            startRxThread()
            startPlayoutThread()
            startStatsThread()

            AppLog.i(this, "AudioSession started successfully")
        } catch (e: Exception) {
            AppLog.i(this, "AudioSession start failed: ${e.message}")
        }
    }

    private fun setupSockets() {
        txSocket = DatagramSocket().apply { reuseAddress = true }
        rxSocket = DatagramSocket(myPort).apply { reuseAddress = true }
    }

    private fun setupAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf * 2, FRAME_BYTES * 4)
        )
        audioRecord?.startRecording()
    }

    private fun setupAudioTrack() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        audioTrack = AudioTrack(
            attrs, format, max(minBuf * 4, FRAME_BYTES * 8),
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply { play() }

        // 强制最大音量
        try {
            val am = context.getSystemService(AudioManager::class.java)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.9).toInt(), 0)
        } catch (_: Exception) {}
    }

    private fun startTxThread() {
        txThread = thread(name = "AudioTx") {
            val buffer = ShortArray(FRAME_SAMPLES)
            val byteBuffer = ByteArray(FRAME_BYTES)

            while (running) {
                try {
                    val read = audioRecord?.read(buffer, 0, FRAME_SAMPLES) ?: 0
                    if (read > 0) {
                        // MIC 增益
                        for (i in 0 until read) {
                            val amp = (buffer[i] * MIC_GAIN).toInt()
                            buffer[i] = amp.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }

                        val peak = pcm16lePeak(buffer)
                        if (ENABLE_NOISE_GATE && peak < NOISE_GATE) {
                            txSocket?.send(DatagramPacket(silenceFrame, 0, FRAME_BYTES, peerIp, peerPort))
                        } else {
                            for (i in 0 until read) {
                                byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                                byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                            }
                            txSocket?.send(DatagramPacket(byteBuffer, 0, read * 2, peerIp, peerPort))
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun startRxThread() {
        rxThread = thread(name = "AudioRx") {
            val buf = ByteArray(FRAME_BYTES * 2)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    rxSocket?.receive(p) ?: continue
                    if (p.length == FRAME_BYTES) {
                        val data = buf.copyOf(FRAME_BYTES)
                        synchronized(jitterBuffer) {
                            if (jitterBuffer.size >= JITTER_MAX) jitterBuffer.removeFirst()
                            jitterBuffer.addLast(data)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun startPlayoutThread() {
        playThread = thread(name = "AudioPlayout") {
            val shortBuf = ShortArray(FRAME_SAMPLES)
            while (running) {
                val toPlay = synchronized(jitterBuffer) {
                    if (jitterBuffer.size >= PREBUFFER) jitterBuffer.removeFirst() else null
                }

                if (toPlay != null) {
                    for (i in 0 until FRAME_SAMPLES) {
                        val s = ((toPlay[i * 2].toInt() and 0xFF) or ((toPlay[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
                        val amplified = (s * RX_GAIN).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        shortBuf[i] = amplified
                    }
                    lastRxPeak = pcm16lePeak(shortBuf)
                    audioTrack?.write(shortBuf, 0, FRAME_SAMPLES)
                } else {
                    Thread.sleep(5)
                }
            }
        }
    }

    private fun startStatsThread() {
        statsThread = thread(name = "AudioStats") {
            while (running) {
                Thread.sleep(1000)
                val jitterSize = synchronized(jitterBuffer) { jitterBuffer.size }
                AppLog.i(this, "Audio stats: jitterQ=$jitterSize rxPeak=$lastRxPeak gate=$ENABLE_NOISE_GATE")
            }
        }
    }

    fun stop() {
        running = false
        listOf(txThread, rxThread, playThread, statsThread).forEach { it?.interrupt() }

        audioRecord?.stop(); audioRecord?.release()
        audioTrack?.stop(); audioTrack?.release()
        txSocket?.close(); rxSocket?.close()

        jitterBuffer.clear()
        AppLog.i(this, "AudioSession stopped")
    }

    private fun pcm16lePeak(buf: ShortArray): Int {
        var max = 0
        for (s in buf) max = max(max, abs(s.toInt()))
        return max
    }
}
