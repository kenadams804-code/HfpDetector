下面把 **AudioSession.kt** 从你截断的位置开始写完，并且我直接给你一份**完整可替换的整文件**（避免你拼接出错）。这个版本包含：

- **AEC 开启**（抑制回授）
- **默认关闭 NS/AGC**（减少“风声/吹气伪影”）
- **Noise Gate（噪声门限 + hangover）**（把小幅度风噪发成静音帧）
- **Jitter Buffer（入队 + 播放端用“对齐时钟”的方式取帧）**，并且当队列太大时会**丢帧追赶**，避免延迟越积越大导致“恶心/眩晕”
- stats 日志：`jitterQ`、`micPeak`

---

## 整文件替换：AudioSession.kt
路径：`app/src/main/java/com/example/hfpdetector/AudioSession.kt`

```kotlin
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
    private val FRAME_BYTES = 640 // 20ms @16kHz mono 16bit

    private val JITTER_MAX = 10   // 最大缓存帧数（≈200ms）
    private val PREBUFFER = 3     // 预缓冲帧数（≈60ms）
    private val CATCHUP_TARGET = 5 // 队列超过这个值就丢帧追赶，避免延迟堆积

    // ✅ 减少“风声/水声伪影”：只开 AEC，先关 NS/AGC（后续可做成设置项）
    private val ENABLE_AEC = true
    private val ENABLE_NS = false
    private val ENABLE_AGC = false

    // ✅ 噪声门限：低于门限视作噪声，发静音帧
    private val NOISE_GATE = 220          // 可在 120~400 之间调
    private val HANGOVER_FRAMES = 10      // 200ms 余留，避免说话断续
    private var hangover = 0

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

                // 音量不要太大（太大更容易回授、不舒服）
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val target = (maxVol * 0.40f).toInt().coerceAtLeast(1)
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

            // 录音：VOICE_COMMUNICATION 优先，失败降级 MIC
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

            // AEC/NS/AGC
            val sid = rec.audioSessionId

            runCatching {
                if (ENABLE_AEC && AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：AEC enabled=${aec?.enabled}")
                } else {
                    AppLog.i(context, "AudioSession：AEC disabled/unavailable")
                }
            }

            runCatching {
                if (ENABLE_NS && NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：NS enabled=${ns?.enabled}")
                } else {
                    AppLog.i(context, "AudioSession：NS disabled/unavailable")
                }
            }

            runCatching {
                if (ENABLE_AGC && AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sid)?.apply { enabled = true }
                    AppLog.i(context, "AudioSession：AGC enabled=${agc?.enabled}")
                } else {
                    AppLog.i(context, "AudioSession：AGC disabled/unavailable")
                }
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
                val spk = outs.firstOrNull { it.type ==
