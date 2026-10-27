package com.example.screenmirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * Sistem sesini (diğer uygulamaların çaldığı ses) AudioPlaybackCapture ile yakalar:
 * S16LE, 44100 Hz, stereo, 10 ms'lik parçalar halinde [FrameSink.sendAudio]'ya verir.
 *
 * RECORD_AUDIO izni gerekir. Ses yakalamaya izin vermeyen uygulamalar (bazı DRM'li
 * servisler) sessiz kalır. Telefonun hoparlöründen çalma ayrıca devam eder.
 */
class AudioCapturer(
    private val projection: MediaProjection,
    private val sink: FrameSink,
    /** Yakalama örnekleme hızı: AirPlay 44100, Cast (Opus) 48000. */
    private val sampleRate: Int = SAMPLE_RATE,
) {
    private var record: AudioRecord? = null
    @Volatile private var running = false

    /** true iken telefon sesi Apple TV'ye gönderilmez (sessiz örnek yollanır, akış bozulmaz). */
    @Volatile var muted = false

    @SuppressLint("MissingPermission") // çağıran RECORD_AUDIO iznini kontrol eder
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                // 500 ms: telefon meşgulken okuma gecikse bile örnek kaybolmasın (çıtırtı nedeni)
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4 / 2))
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        running = true
        rec.startRecording()
        thread(name = "audio-capture", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(sampleRate / 100 * 4) // 10 ms, stereo S16
            var last = SystemClock.elapsedRealtime()
            var gaps = 0
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                val now = SystemClock.elapsedRealtime()
                // 10 ms'lik okuma 40 ms'den uzun sürdüyse ses akışı aksamış demektir
                if (now - last > GAP_WARN_MS && ++gaps <= 20) {
                    Log.w(TAG, "okuma boşluğu ${now - last} ms (#$gaps)")
                }
                last = now
                if (n > 0) {
                    if (muted) java.util.Arrays.fill(buf, 0, n, 0)
                    sink.sendAudio(buf, n)
                } else if (n < 0) break
            }
        }
        return true
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        record?.release()
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val GAP_WARN_MS = 40L
        private const val TAG = "ScreenMirrorAudio"
    }
}
