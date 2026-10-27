package com.example.screenmirror.cast

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tek bir Cast Streaming akışı (video veya ses): kareyi AES-CTR ile şifreler, Cast RTP paketlerine böler,
 * RTCP gönderici raporlarını (SR) üretir ve alıcıdan gelen RTCP'yi işler (PLI, Cast ACK/NACK).
 *
 * Paket düzeni Chromium `media/cast/net/rtp/rtp_packetizer.cc` ve `rtcp_builder.cc` ile birebirdir:
 *   RTP (12): 0x80 | (marker<<7 | payloadType) | seq u16 | rtpTimestamp u32 | ssrc u32
 *   Cast (7+): byte0 = 0x40 (referans kare var) | 0x80 (anahtar kare) | uzantı sayısı; frameId&0xFF;
 *              packetId u16; maxPacketId u16; referencedFrameId&0xFF; [uzantı: 0x04 0x02 gecikme_ms u16]
 *   Şifre: AES-128-CTR; sayaç = 16 bayt, [8..11] = frameId (u32, big-endian) ve hepsi aesIvMask ile XOR'lanır.
 */
class CastStream(
    private val socket: DatagramSocket,
    private val dest: SocketAddress,
    val ssrc: Int,
    private val receiverSsrc: Int,
    private val payloadType: Int,
    private val timebase: Int,
    private val aesKey: ByteArray,
    private val ivMask: ByteArray,
    private val playoutDelayMs: Int,
    private val onPli: () -> Unit,
    private val tag: String,
) {
    private class Stored(val frameId: Int, val packets: Array<ByteArray>)

    private val keySpec = SecretKeySpec(aesKey, "AES")
    private val stored = ArrayDeque<Stored>()
    private var frameId = 0
    private var seq = (System.nanoTime() and 0x7FFF).toInt()
    private var packetCount = 0
    private var octetCount = 0L
    private var lastRtp = 0L
    private var lastRefNs = 0L
    private var framesSent = 0

    @Volatile var lastAckedFrame = -1
        private set

    /** Alıcı gerçekten bir şey geri bildirdi mi (bağlantı canlı mı). */
    @Volatile var feedbackReceived = false
        private set

    /**
     * [plain]: kodlanmış kare (video: Annex-B, SPS/PPS anahtar karede başta; ses: tek Opus paketi).
     * [rtpTimestamp]: akışın zaman tabanında; [refNs]: karenin System.nanoTime() cinsinden yakalama zamanı.
     */
    @Synchronized
    fun sendFrame(plain: ByteArray, key: Boolean, rtpTimestamp: Long, refNs: Long) {
        if (plain.isEmpty()) return
        val id = frameId++
        val data = encrypt(plain, id)

        // İlk karelerde alıcı oynatma zamanını hesaplayabilsin diye her karenin önüne SR koy (Chrome: 100 kare)
        if (framesSent < AGGRESSIVE_REPORTS) {
            lastRtp = rtpTimestamp
            lastRefNs = refNs
            sendSenderReport()
        }
        framesSent++
        lastRtp = rtpTimestamp
        lastRefNs = refNs

        val headers = RTP_HEADER + CAST_HEADER + EXT_HEADER
        val maxData = MAX_PACKET - headers
        val numPackets = (data.size + maxData - 1) / maxData
        val perPacket = (data.size + numPackets - 1) / numPackets // Chrome da eşit böler
        val packets = ArrayList<ByteArray>(numPackets)
        var off = 0
        for (pid in 0 until numPackets) {
            val len = minOf(perPacket, data.size - off)
            val last = pid == numPackets - 1
            val ext = pid == 0
            val p = ByteArray(RTP_HEADER + CAST_HEADER + (if (ext) EXT_HEADER else 0) + len)
            var i = 0
            p[i++] = 0x80.toByte()
            p[i++] = (payloadType or (if (last) 0x80 else 0)).toByte()
            p[i++] = (seq ushr 8).toByte(); p[i++] = seq.toByte()
            seq = (seq + 1) and 0xFFFF
            val ts = rtpTimestamp.toInt()
            p[i++] = (ts ushr 24).toByte(); p[i++] = (ts ushr 16).toByte(); p[i++] = (ts ushr 8).toByte(); p[i++] = ts.toByte()
            p[i++] = (ssrc ushr 24).toByte(); p[i++] = (ssrc ushr 16).toByte(); p[i++] = (ssrc ushr 8).toByte(); p[i++] = ssrc.toByte()
            var b0 = 0x40 // referans kare kimliği bayt'ı var
            if (key) b0 = b0 or 0x80
            if (ext) b0 = b0 or 1 // 1 uzantı (uyarlanabilir gecikme)
            p[i++] = b0.toByte()
            p[i++] = (id and 0xFF).toByte()
            p[i++] = (pid ushr 8).toByte(); p[i++] = pid.toByte()
            val maxId = numPackets - 1
            p[i++] = (maxId ushr 8).toByte(); p[i++] = maxId.toByte()
            p[i++] = ((if (key) id else id - 1) and 0xFF).toByte()
            if (ext) {
                p[i++] = (1 shl 2).toByte() // kCastRtpExtensionAdaptiveLatency << 2
                p[i++] = 2
                p[i++] = (playoutDelayMs ushr 8).toByte(); p[i++] = playoutDelayMs.toByte()
            }
            System.arraycopy(data, off, p, i, len)
            off += len
            packets.add(p)
        }

        stored.addLast(Stored(id, packets.toTypedArray()))
        while (stored.size > MAX_STORED_FRAMES) stored.removeFirst()

        var n = 0
        for (p in packets) {
            send(p)
            // Büyük anahtar karede alıcının soket tamponunu taşırmamak için hafif aralık
            if (++n % PACE_EVERY == 0) {
                try {
                    Thread.sleep(0, PACE_NS)
                } catch (_: InterruptedException) {
                }
            }
        }
        packetCount += packets.size
        octetCount += data.size
    }

    /** Gönderici raporu (SR): NTP saati ile RTP zamanını eşler (ses/görüntü senkronu için). */
    @Synchronized
    fun sendSenderReport() {
        if (lastRefNs == 0L) return
        val nowNs = System.nanoTime()
        val rtpNow = lastRtp + (nowNs - lastRefNs) * timebase / 1_000_000_000L
        val wallMs = System.currentTimeMillis()
        val ntpSec = (wallMs / 1000) + NTP_UNIX_OFFSET
        val ntpFrac = ((wallMs % 1000) * 0x1_0000_0000L / 1000)
        val p = ByteArray(28)
        var i = 0
        p[i++] = 0x80.toByte(); p[i++] = 200.toByte() // SR
        p[i++] = 0; p[i++] = 6 // uzunluk (kelime - 1)
        i = putInt(p, i, ssrc)
        i = putInt(p, i, ntpSec.toInt())
        i = putInt(p, i, ntpFrac.toInt())
        i = putInt(p, i, rtpNow.toInt())
        i = putInt(p, i, packetCount)
        putInt(p, i, octetCount.toInt())
        send(p)
    }

    /** Alıcıdan gelen RTCP (bileşik paket) işlenir. */
    @Synchronized
    fun onRtcp(d: ByteArray, len: Int) {
        var pos = 0
        while (pos + 8 <= len) {
            val fmt = d[pos].toInt() and 0x1F
            val pt = d[pos + 1].toInt() and 0xFF
            val words = ((d[pos + 2].toInt() and 0xFF) shl 8) or (d[pos + 3].toInt() and 0xFF)
            val total = (words + 1) * 4
            if (pos + total > len) break
            val senderSsrc = readInt(d, pos + 4)
            if (senderSsrc == receiverSsrc) {
                if (pt == 206) { // payload-specific geri bildirim
                    if (fmt == 1) { // PLI
                        feedbackReceived = true
                        Log.d(TAG, "$tag: PLI")
                        onPli()
                    } else if (fmt == 15 && total >= 20) { // Cast geri bildirimi
                        val name = readInt(d, pos + 12)
                        if (name == CAST_TAG) parseCastFeedback(d, pos + 16, pos + total)
                    }
                } else if (pt == 201 || pt == 207) { // RR / XR: alıcı canlı
                    feedbackReceived = true
                }
            }
            pos += total
        }
    }

    private fun parseCastFeedback(d: ByteArray, start: Int, end: Int) {
        if (start + 4 > end) return
        feedbackReceived = true
        val ack8 = d[start].toInt() and 0xFF
        val loss = d[start + 1].toInt() and 0xFF
        var p = start + 4
        // ACK: bu karede ve öncesindekileri sakla-listeden at
        val ackFull = expand8(ack8)
        if (ackFull >= 0) {
            lastAckedFrame = ackFull
            while (stored.isNotEmpty() && stored.first().frameId <= ackFull) stored.removeFirst()
        }
        for (k in 0 until loss) {
            if (p + 4 > end) break
            val f8 = d[p].toInt() and 0xFF
            val pid = ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF)
            val mask = d[p + 3].toInt() and 0xFF
            p += 4
            val fr = stored.firstOrNull { (it.frameId and 0xFF) == f8 } ?: continue
            if (pid == ALL_PACKETS_LOST) {
                fr.packets.forEach { send(it) }
            } else {
                fr.packets.getOrNull(pid)?.let { send(it) }
                for (bit in 0..7) {
                    if ((mask and (1 shl bit)) != 0) fr.packets.getOrNull(pid + 1 + bit)?.let { send(it) }
                }
            }
        }
    }

    /** 8 bitlik kare kimliğini gönderilmiş son karelere göre tam değere genişletir (yoksa -1). */
    private fun expand8(v: Int): Int {
        val cur = frameId - 1
        if (cur < 0) return -1
        var cand = (cur and 0xFF.inv()) or v
        if (cand > cur) cand -= 256
        return if (cand >= 0) cand else -1
    }

    private fun encrypt(plain: ByteArray, id: Int): ByteArray {
        val nonce = ByteArray(16)
        nonce[8] = (id ushr 24).toByte()
        nonce[9] = (id ushr 16).toByte()
        nonce[10] = (id ushr 8).toByte()
        nonce[11] = id.toByte()
        for (i in 0 until 16) nonce[i] = (nonce[i].toInt() xor ivMask[i].toInt()).toByte()
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))
        return c.doFinal(plain)
    }

    private fun send(p: ByteArray) {
        try {
            socket.send(DatagramPacket(p, p.size, dest))
        } catch (_: Exception) {
        }
    }

    private fun putInt(b: ByteArray, at: Int, v: Int): Int {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
        return at + 4
    }

    private fun readInt(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    companion object {
        private const val TAG = "ScreenMirrorCast"
        private const val RTP_HEADER = 12
        private const val CAST_HEADER = 7
        private const val EXT_HEADER = 4
        private const val MAX_PACKET = 1200 // MTU'ya sığsın (Chrome 1472 kullanır)
        private const val MAX_STORED_FRAMES = 120 // kMaxUnackedFrames
        private const val AGGRESSIVE_REPORTS = 100 // kNumAggressiveReportsSentAtStart
        private const val PACE_EVERY = 12
        private const val PACE_NS = 400_000
        private const val ALL_PACKETS_LOST = 0xFFFF
        private const val CAST_TAG = 0x43415354 // 'CAST'
        private const val NTP_UNIX_OFFSET = 2_208_988_800L
    }
}
