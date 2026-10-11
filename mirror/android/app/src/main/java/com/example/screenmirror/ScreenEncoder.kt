package com.example.screenmirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface

/**
 * MediaProjection -> VirtualDisplay -> MediaCodec (H.264, düşük gecikme) -> FrameSink.
 * Ekran yüzeyi doğrudan encoder'ın input surface'ine çizilir (CPU kopyası yok).
 *
 * Ekran döndüğünde [resize] çağrılır. Android 14'te aynı MediaProjection ile ikinci bir
 * VirtualDisplay açılamaz; bu yüzden VirtualDisplay korunur, sadece encoder ve yüzey değişir.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private var width: Int,
    private var height: Int,
    private val dpi: Int,
    private val sender: FrameSink,
    private val bitrate: Int = 6_000_000,
    private val fps: Int = 60,
) {
    private var display: VirtualDisplay? = null
    private var drain: Drain? = null
    private val lock = Any()

    val currentWidth get() = width
    val currentHeight get() = height

    fun start() = synchronized(lock) {
        val d = Drain(width, height)
        display = projection.createVirtualDisplay(
            "screen-mirror", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            d.surface, null, null,
        )
        d.begin()
        drain = d
    }

    /** Ekran döndü veya tuval değişti: yeni boyutla encoder'ı yeniden kur. */
    fun resize(newWidth: Int, newHeight: Int) = synchronized(lock) {
        val disp = display ?: return@synchronized
        if (newWidth == width && newHeight == height) return@synchronized

        val old = drain
        val fresh = Drain(newWidth, newHeight) // yeni codec + yüzey hazır
        disp.setSurface(fresh.surface)
        disp.resize(newWidth, newHeight, dpi)
        old?.close() // eski codec artık kare üretmiyor
        width = newWidth
        height = newHeight
        fresh.begin()
        drain = fresh
    }

    fun requestKeyFrame() {
        drain?.requestKeyFrame()
    }

    fun stop() = synchronized(lock) {
        drain?.close()
        drain = null
        display?.release()
        display = null
    }

    /** Tek bir codec örneği ve onu boşaltan thread. */
    private inner class Drain(w: Int, h: Int) {
        private val codec: MediaCodec
        val surface: Surface

        @Volatile private var running = false
        private var thread: Thread? = null

        init {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // Baseline: B-frame / reorder yok => ekstra gecikme yok
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                // Level 4.1 en çok 50 Mbps'ye izin verir; daha yüksek bit hızında 5.1 bildir
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    if (bitrate > 50_000_000) MediaCodecInfo.CodecProfileLevel.AVCLevel51
                    else MediaCodecInfo.CodecProfileLevel.AVCLevel41,
                )
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger("low-latency", 1) // KEY_LOW_LATENCY (API 30); desteklemeyen encoder yok sayar
                // Ekran durağanken de periyodik kare gönder (kayıptan toparlanma + alıcıda donma olmaması)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        }

        fun begin() {
            running = true
            thread = Thread({ drainLoop() }, "encoder-drain").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
        }

        fun requestKeyFrame() {
            try {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: IllegalStateException) {
            }
        }

        private fun drainLoop() {
            val info = MediaCodec.BufferInfo()
            var config: ByteArray? = null
            var scratch = ByteArray(256 * 1024)
            while (running) {
                val idx = try {
                    codec.dequeueOutputBuffer(info, 10_000)
                } catch (_: IllegalStateException) {
                    break
                }
                if (idx < 0) continue

                val out = codec.getOutputBuffer(idx)
                if (out != null && info.size > 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        config = ByteArray(info.size).also { out.get(it) }
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val cfg = if (key) config else null
                        val prefix = cfg?.size ?: 0
                        val total = prefix + info.size
                        if (scratch.size < total) scratch = ByteArray(total * 2)
                        if (cfg != null) System.arraycopy(cfg, 0, scratch, 0, prefix)
                        out.get(scratch, prefix, info.size)
                        sender.sendFrame(scratch, total, key)
                    }
                }
                try {
                    codec.releaseOutputBuffer(idx, false)
                } catch (_: IllegalStateException) {
                    break
                }
            }
        }

        fun close() {
            running = false
            thread?.join(500)
            try {
                codec.stop()
            } catch (_: IllegalStateException) {
            }
            codec.release()
            surface.release()
        }
    }
}
