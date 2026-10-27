package com.altinvolkan.imirror.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * H.264 Annex-B access unit'lerini MediaCodec ile çözüp yüzeye basar (düşük gecikme: çıkış kareleri
 * hemen gösterilir). Çözünürlük değişince (telefon döndü) SPS farklı gelir; codec yeniden kurulur.
 *
 * [feed] yalnızca ağ iş parçacığından çağrılır.
 */
class H264Decoder(
    private val surfaceProvider: () -> Surface?,
    private val onSize: (width: Int, height: Int) -> Unit,
    private val onNeedKeyframe: () -> Unit,
) {
    @Volatile private var codec: MediaCodec? = null
    private var spsBytes: ByteArray? = null
    private var ptsUs = 0L

    @Synchronized
    fun feed(au: ByteArray, key: Boolean) {
        if (key) {
            val sps = extractSps(au)
            if (codec == null || (sps != null && !sps.contentEquals(spsBytes))) {
                restart(sps)
            }
        }
        val c = codec ?: return onNeedKeyframe()
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx < 0) return onNeedKeyframe() // çözücü yetişemiyor: kareyi at, yenisini iste
            val buf = c.getInputBuffer(idx)!!
            if (au.size > buf.capacity()) {
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                return onNeedKeyframe()
            }
            buf.clear()
            buf.put(au)
            ptsUs += 16_000
            c.queueInputBuffer(idx, 0, au.size, ptsUs, 0)
        } catch (_: Exception) {
            release()
            onNeedKeyframe()
        }
    }

    @Synchronized
    fun release() {
        val c = codec ?: return
        codec = null
        spsBytes = null
        try {
            c.stop()
        } catch (_: Exception) {
        }
        try {
            c.release()
        } catch (_: Exception) {
        }
    }

    private fun restart(sps: ByteArray?) {
        release()
        val surface = surfaceProvider() ?: return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                setInteger("low-latency", 1) // KEY_LOW_LATENCY (API 30); desteklemeyen codec yok sayar
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            spsBytes = sps
            Thread({ outputLoop(c) }, "tv-decoder-out").also { it.priority = Thread.MAX_PRIORITY }.start()
        } catch (_: Exception) {
            codec = null
            onNeedKeyframe()
        }
    }

    private fun outputLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (codec === c) {
            try {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    idx >= 0 -> c.releaseOutputBuffer(idx, true) // hemen göster
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportSize(c.outputFormat)
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun reportSize(f: MediaFormat) {
        var w = f.getInteger(MediaFormat.KEY_WIDTH)
        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
        if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
        }
        if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        }
        onSize(w, h)
    }

    /** İlk SPS NAL'ını (7) başlangıç kodu olmadan döndürür. */
    private fun extractSps(au: ByteArray): ByteArray? {
        var i = 0
        while (i + 4 < au.size) {
            val three = au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1
            if (three) {
                val start = i + 3
                if ((au[start].toInt() and 0x1F) == 7) {
                    var end = au.size
                    var j = start + 1
                    while (j + 2 < au.size) {
                        if (au[j].toInt() == 0 && au[j + 1].toInt() == 0 && (au[j + 2].toInt() == 1 ||
                                (au[j + 2].toInt() == 0 && j + 3 < au.size && au[j + 3].toInt() == 1))
                        ) {
                            end = j
                            break
                        }
                        j++
                    }
                    return au.copyOfRange(start, end)
                }
                i = start
            } else {
                i++
            }
        }
        return null
    }
}
