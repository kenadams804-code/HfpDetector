package com.example.hfpdetector

import android.content.Context
import android.media.*
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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

    fun start() {
        running = true

        val am = context.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true

        val sampleRate = 16000
        val inConfig = AudioFormat.CHANNEL_IN_MONO
        val outConfig = AudioFormat.CHANNEL_OUT_MONO
        val fmt = AudioFormat.ENCODING_PCM_16BIT

        val minIn = AudioRecord.getMinBufferSize(sampleRate, inConfig, fmt)
        val minOut = AudioTrack.getMinBufferSize(sampleRate, outConfig, fmt)

        val recBuf = max(minIn, 2048)
        val playBuf = max(minOut, 2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            inConfig,
            fmt,
            recBuf * 2
        )

        audioTrack = if (Build.VERSION.SDK_INT >= 21) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val af = AudioFormat.Builder()
                .setEncoding(fmt)
                .setSampleRate(sampleRate)
                .setChannelMask(outConfig)
                .build()
            AudioTrack(attr, af, playBuf * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRate, outConfig, fmt, playBuf * 2, AudioTrack.MODE_STREAM)
        }

        sock = DatagramSocket(myAudioPort)

        audioRecord?.startRecording()
        audioTrack?.play()

        startSendLoop()
        startRecvLoop()
    }

    private fun startSendLoop() {
        thread(name = "aud-send") {
            val rec = audioRecord ?: return@thread
            val s = sock ?: return@thread
            val peerAddr = InetAddress.getByName(peerIp)
            val buf = ByteArray(640) // 20ms @16kHz mono 16-bit => 320 samples => 640 bytes

            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    try {
                        val p = DatagramPacket(buf, n, peerAddr, peerAudioPort)
                        s.send(p)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun startRecvLoop() {
        thread(name = "aud-recv") {
            val tr = audioTrack ?: return@thread
            val s = sock ?: return@thread
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    s.receive(p)
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
