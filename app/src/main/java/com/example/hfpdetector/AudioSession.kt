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

    @Volatile private var sentBytes: Long = 0
    @Volatile private var recvBytes: Long = 0

    fun start() {
        if (running) return
        running = true

        try {
            AppLog.i(context, "AudioSession：start peer=$peerIp:$peerAudioPort myPort=$myAudioPort")

            val am = context.getSystemService(AudioManager::class.java)
            runCatching {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
            }

            val sampleRate = 16000
            val inConfig = AudioFormat.CHANNEL_IN_MONO
            val outConfig = AudioFormat.CHANNEL_OUT_MONO
            val fmt = AudioFormat.ENCODING_PCM_16BIT

            val minIn = AudioRecord.getMinBufferSize(sampleRate, inConfig, fmt)
            val minOut = AudioTrack.getMinBufferSize(sampleRate, outConfig, fmt)

            val recBuf = max(minIn, 2048)
            val playBuf = max(minOut, 2048)

            audioRecord = tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, inConfig, fmt, recBuf * 2)
                ?: tryCreateAudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, inConfig, fmt, recBuf * 2)

            val rec = audioRecord
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession：AudioRecord 初始化失败")
                stop(); return
            }

            audioTrack = tryCreateAudioTrack(sampleRate, outConfig, fmt, playBuf * 2)
            val tr = audioTrack
            if (tr == null || tr.state != AudioTrack.STATE_INITIALIZED) {
                AppLog.i(context, "AudioSession：AudioTrack 初始化失败")
                stop(); return
            }

            sock = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(myAudioPort))
                }
            } catch (t: Throwable) {
                AppLog.i(context, "AudioSession：绑定 UDP 端口失败 myAudioPort=$myAudioPort err=${t.javaClass.simpleName} ${t.message}")
                stop(); return
            }

            runCatching { rec.startRecording() }.onFailure {
                AppLog.i(context, "AudioSession：startRecording 失败：${it.javaClass.simpleName} ${it.message}")
                stop(); return
            }

            runCatching {
                tr.play()
                tr.setVolume(1.0f)
            }.onFailure {
                AppLog.i(context, "AudioSession：AudioTrack play 失败：${it.javaClass.simpleName} ${it.message}")
                stop(); return
            }

            // ✅ 日志：每 3 秒打印一次收发字节数，判断是否通
            startStatsLoop()

            startSendLoop()
            startRecvLoop()

            AppLog.i(context, "AudioSession：已启动（send/recv 线程已起）")
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
                AppLog.i(context, "AudioSession：音频流量 发送=${ds}B/3s 接收=${dr}B/3s (totalS=$s totalR=$r)")
            }
        }
    }

    private fun tryCreateAudioRecord(source: Int, sampleRate: Int, inConfig: Int, fmt: Int, bufferBytes: Int): AudioRecord? {
        return try {
            AudioRecord(source, sampleRate, inConfig, fmt, bufferBytes)
        } catch (t: Throwable) {
            AppLog.i(context, "AudioSession：AudioRecord 构造失败 source=$source err=${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun tryCreateAudioTrack(sampleRate: Int, outConfig: Int, fmt: Int, bufferBytes: Int): AudioTrack? {
        return try {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val af = AudioFormat.Builder()
                .setEncoding(fmt)
                .setSampleRate(sampleRate)
                .setChannelMask(outConfig)
                .build()
            AudioTrack(attr, af, bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (t: Throwable) {
            AppLog.i(context, "AudioSession：AudioTrack 构造失败 err=${t.javaClass.simpleName} ${t.message}")
            null
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
    }
}
