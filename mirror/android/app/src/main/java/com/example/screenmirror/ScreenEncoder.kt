package com.example.screenmirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle

/**
 * MediaProjection -> VirtualDisplay -> MediaCodec (H.264, düşük gecikme) -> UdpVideoSender.
 * Ekran yüzeyi doğrudan encoder'ın input surface'ine çizilir (CPU kopyası yok).
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val sender: FrameSink,
    private val bitrate: Int = 6_000_000,
    private val fps: Int = 60,
) {
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // Baseline: B-frame / reorder yok => ekstra gecikme yok
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger("low-latency", 1) // KEY_LOW_LATENCY (API 30); desteklemeyen encoder yok sayar
            // Ekran durağanken de periyodik kare gönder (kayıptan toparlanma + alıcıda donma olmaması)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
        }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()
        codec = c

        display = projection.createVirtualDisplay(
            "screen-mirror", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null,
        )

        running = true
        thread = Thread({ drainLoop(c) }, "encoder-drain").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: IllegalStateException) {
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config: ByteArray? = null
        var scratch = ByteArray(256 * 1024)
        while (running) {
            val idx = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (_: IllegalStateException) {
                break
            }
            if (idx < 0) continue

            val out = c.getOutputBuffer(idx)
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
            c.releaseOutputBuffer(idx, false)
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        display?.release()
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
        }
        codec?.release()
        codec = null
        display = null
    }
}
