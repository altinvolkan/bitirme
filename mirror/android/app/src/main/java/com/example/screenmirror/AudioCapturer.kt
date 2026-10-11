package com.example.screenmirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.concurrent.thread

/**
 * Sistem sesini (diğer uygulamaların çaldığı ses) AudioPlaybackCapture ile yakalar:
 * S16LE, 44100 Hz, stereo, 20 ms'lik parçalar halinde [FrameSink.sendAudio]'ya verir.
 *
 * RECORD_AUDIO izni gerekir. Ses yakalamaya izin vermeyen uygulamalar (bazı DRM'li
 * servisler) sessiz kalır. Telefonun hoparlöründen çalma ayrıca devam eder.
 */
class AudioCapturer(
    private val projection: MediaProjection,
    private val sink: FrameSink,
) {
    private var record: AudioRecord? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // çağıran RECORD_AUDIO iznini kontrol eder
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
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
            val buf = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) sink.sendAudio(buf, n) else if (n < 0) break
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
        private const val CHUNK_BYTES = SAMPLE_RATE / 50 * 4 // 20 ms, stereo S16
    }
}
