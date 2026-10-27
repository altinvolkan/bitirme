package com.altinvolkan.imirror.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Telefondan gelen PCM sesini (S16LE, 44100 Hz, stereo) çalar. Küçük bir tampon tutar; geride
 * kalırsa en eski parçaları atarak gecikmeyi sınırlar.
 */
class AudioPlayer {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var lastSeq = 0
    private var hasSeq = false

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            Proto.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Proto.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minBuf, Proto.SAMPLE_RATE * 4 / 10)) // ~100 ms
                .build()
        } catch (_: Exception) {
            return
        }
        track = t
        running = true
        t.play()
        thread = Thread({
            while (running) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    t.write(chunk, 0, chunk.size)
                } catch (_: Exception) {
                    break
                }
            }
        }, "tv-audio").also { it.start() }
    }

    /** [seq]: paket sırası; kayıp varsa aradaki süre kadar sessizlik eklenir (en çok ~130 ms). */
    fun push(seq: Int, pcm: ByteArray) {
        if (!running) return
        if (hasSeq) {
            val gap = seq - lastSeq - 1
            if (gap in 1..20) repeat(gap) { queue.offer(ByteArray(pcm.size)) }
        }
        lastSeq = seq
        hasSeq = true
        queue.offer(pcm)
        // geride kalındıysa (~160 ms'den fazla) en eskileri at
        while (queue.size > 24) queue.poll()
    }

    fun reset() {
        queue.clear()
        hasSeq = false
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null
        queue.clear()
        hasSeq = false
    }
}
