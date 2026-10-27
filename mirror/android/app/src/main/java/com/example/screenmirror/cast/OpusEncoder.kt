package com.example.screenmirror.cast

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * PCM (S16LE, 48 kHz, stereo) -> Opus. Android'in yerleşik Opus kodlayıcısı (API 29+) çıkış olarak ham Opus
 * paketleri verir (Ogg yok); ilk çıkış codec-config'tir ve gönderilmez. Her çıkış bir Cast ses karesidir.
 */
class OpusEncoder(
    bitrate: Int,
    /** (opusPaketi, ptsMikrosaniye) */
    private val onPacket: (ByteArray, Long) -> Unit,
) {
    private val codec: MediaCodec
    @Volatile private var running = true
    private var samples = 0L // girişe verilen örnek sayısı (kanal başına)
    private val thread: Thread

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        thread = Thread({ drain() }, "cast-opus-out").also { it.start() }
    }

    /** Ses yakalama iş parçacığından çağrılır. */
    fun feed(pcm: ByteArray, size: Int) {
        var off = 0
        while (off < size && running) {
            val idx = try {
                codec.dequeueInputBuffer(10_000)
            } catch (_: Exception) {
                return
            }
            if (idx < 0) return // kodlayıcı yetişemiyor: parçayı at
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            val n = minOf(buf.remaining(), size - off) and 3.inv() // 4 baytlık örnek sınırı
            if (n <= 0) {
                codec.queueInputBuffer(idx, 0, 0, 0, 0)
                return
            }
            buf.put(pcm, off, n)
            val ptsUs = samples * 1_000_000L / SAMPLE_RATE
            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
            samples += n / 4
            off += n
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (_: Exception) {
                break
            }
            if (idx < 0) continue
            try {
                val out = codec.getOutputBuffer(idx)
                if (out != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val b = ByteArray(info.size)
                    out.position(info.offset)
                    out.get(b)
                    onPacket(b, info.presentationTimeUs)
                }
                codec.releaseOutputBuffer(idx, false)
            } catch (_: Exception) {
                break
            }
        }
    }

    fun close() {
        running = false
        thread.join(300)
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
    }

    companion object {
        const val SAMPLE_RATE = 48000

        /** Bu cihazda Opus kodlayıcısı var mı? (yoksa Cast akışı sessiz yapılır) */
        fun isAvailable(): Boolean = try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            c.release()
            true
        } catch (_: Exception) {
            false
        }
    }
}
