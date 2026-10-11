package com.example.screenmirror

import android.content.Context
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
    /**
     * Verilirse görüntü bir GL katmanından geçer: kare hızı [fps]'e sınırlanır (120 Hz ekranlar bile
     * seçilen hızda kalır) ve premium değilse filigran basılır.
     */
    private val watermarkContext: Context? = null,
    /** Filigran için görüntünün yanlarına/üstüne-altına eklenen siyah bant kalınlığı (px, 16'nın katı). */
    private var padX: Int = 0,
    private var padY: Int = 0,
) {
    private var display: VirtualDisplay? = null
    private var drain: Drain? = null
    private val lock = Any()

    val currentWidth get() = width
    val currentHeight get() = height

    fun start() = synchronized(lock) {
        val d = Drain(width, height, padX, padY)
        display = projection.createVirtualDisplay(
            "screen-mirror", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            d.surface, null, null,
        )
        d.begin()
        drain = d
    }

    /** Ekran döndü veya tuval değişti: yeni boyutla encoder'ı yeniden kur. */
    fun resize(newWidth: Int, newHeight: Int, newPadX: Int = 0, newPadY: Int = 0) = synchronized(lock) {
        val disp = display ?: return@synchronized
        if (newWidth == width && newHeight == height) return@synchronized

        val old = drain
        padX = newPadX
        padY = newPadY
        val fresh = Drain(newWidth, newHeight, newPadX, newPadY) // yeni codec + yüzey hazır
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

    /**
     * Tek bir codec örneği ve onu boşaltan thread. [cw]x[ch] = telefon görüntüsü; [px]/[py] = filigran
     * bantları. Codec kare boyutu (cw+2px)x(ch+2py)'dir. Filigran kurulamazsa bantsız yeniden kurulur.
     */
    private inner class Drain(cw: Int, ch: Int, px: Int, py: Int) {
        private lateinit var codec: MediaCodec
        private lateinit var encoderSurface: Surface

        /** VirtualDisplay'in çizeceği yüzey: filigran varsa renderer'ın, yoksa doğrudan encoder'ın. */
        val surface: Surface
        private var watermark: WatermarkRenderer? = null

        @Volatile private var running = false
        private var thread: Thread? = null

        init {
            var target: Surface
            val ctx = watermarkContext
            if (ctx != null) {
                configure(cw + 2 * px, ch + 2 * py)
                val r = WatermarkRenderer(ctx, cw + 2 * px, ch + 2 * py, cw, ch, px, py, fps, encoderSurface)
                val input = r.start()
                if (input != null) {
                    watermark = r
                    target = input
                } else {
                    // GL kurulamadı: bantsız, doğrudan yeniden kur
                    releaseCodec()
                    configure(cw, ch)
                    target = encoderSurface
                }
            } else {
                configure(cw, ch)
                target = encoderSurface
            }
            surface = target
        }

        private fun configure(w: Int, h: Int) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // Baseline: B-frame / reorder yok => ekstra gecikme yok
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, levelFor(w, h))
                // GL katmanı yoksa (yedek yol) kare hızını encoder'a sınırlat; desteklemeyen codec yok sayar
                setInteger("max-fps-to-encoder", fps)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger("low-latency", 1) // KEY_LOW_LATENCY (API 30); desteklemeyen encoder yok sayar
                // Ekran durağanken de periyodik kare gönder (kayıptan toparlanma + alıcıda donma olmaması)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
        }

        /** H.264 seviyesi: 16x16'lık makrobloklar x kare hızı, seviyenin saniyedeki sınırını aşmasın. */
        private fun levelFor(w: Int, h: Int): Int {
            val mbPerSec = ((w + 15) / 16) * ((h + 15) / 16) * fps
            return when {
                mbPerSec <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel41
                mbPerSec <= 522_240 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            }
        }

        private fun releaseCodec() {
            try {
                codec.stop()
            } catch (_: IllegalStateException) {
            }
            codec.release()
            encoderSurface.release()
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
            var frames = 0
            var windowStart = System.nanoTime()
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
                        // Gerçek (ölçülen) kare hızı: arayüzdeki "Kare hızı" bunu gösterir
                        frames++
                        val now = System.nanoTime()
                        if (now - windowStart >= 1_000_000_000L) {
                            LiveInfo.fps = (frames * 1_000_000_000L / (now - windowStart)).toInt()
                            frames = 0
                            windowStart = now
                        }
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
            watermark?.release()
            watermark = null
            releaseCodec()
        }
    }
}
