package com.altinvolkan.imirror.tv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException

/** UDP dinler; video kareyi birleştirip çözücüye, sesi oynatıcıya verir, kayıpta keyframe ister. */
class UdpReceiver(
    private val port: Int,
    private val decoder: H264Decoder,
    private val audio: AudioPlayer,
    private val onStats: (fps: Int, kbps: Int, connected: Boolean) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private val reassembler = FrameReassembler()
    private var peer: SocketAddress? = null
    private var lastKeyframeRequestMs = 0L
    private var lastPacketMs = 0L
    private var frames = 0
    private var bytes = 0
    private var statsAtMs = 0L

    /** Portu açar; açılamazsa false. */
    fun start(): Boolean {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 4 shl 20
                soTimeout = 500
                bind(InetSocketAddress(port))
            }
        } catch (_: Exception) {
            return false
        }
        socket = s
        running = true
        thread = Thread({ loop(s) }, "tv-udp-rx").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    fun stop() {
        running = false
        socket?.close()
        thread?.join(500)
        socket = null
        thread = null
        decoder.release()
        audio.stop()
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        statsAtMs = now()
        while (running) {
            try {
                packet.setData(buf, 0, buf.size)
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                tickStats()
                continue
            } catch (_: Exception) {
                break
            }
            val n = packet.length
            if (n < 1) continue

            // Yeni gönderici (yeni UDP kaynak portu) => yeni oturum
            val from = packet.socketAddress
            if (from != peer) {
                peer = from
                reassembler.reset()
                decoder.release()
                audio.reset()
                requestKeyframe(force = true)
            }
            lastPacketMs = now()
            bytes += n

            when (buf[0]) {
                Proto.TYPE_VIDEO -> handleVideo(buf, n)
                Proto.TYPE_AUDIO -> handleAudio(buf, n)
            }
            tickStats()
        }
    }

    private fun handleVideo(d: ByteArray, n: Int) {
        if (n <= Proto.VIDEO_HEADER) return
        val key = (d[1].toInt() and Proto.FLAG_KEYFRAME) != 0
        val frameId = ((d[2].toInt() and 0xFF) shl 24) or ((d[3].toInt() and 0xFF) shl 16) or
            ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
        val idx = ((d[6].toInt() and 0xFF) shl 8) or (d[7].toInt() and 0xFF)
        val count = ((d[8].toInt() and 0xFF) shl 8) or (d[9].toInt() and 0xFF)

        val frame = reassembler.push(
            frameId, idx, count, key, d.copyOfRange(Proto.VIDEO_HEADER, n),
        )
        if (frame != null) {
            frames++
            decoder.feed(frame, key || isKeyFrame(frame))
        }
        if (reassembler.needKeyframe) requestKeyframe()
    }

    private fun handleAudio(d: ByteArray, n: Int) {
        if (n <= Proto.AUDIO_HEADER) return
        val seq = ((d[2].toInt() and 0xFF) shl 24) or ((d[3].toInt() and 0xFF) shl 16) or
            ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
        audio.push(seq, d.copyOfRange(Proto.AUDIO_HEADER, n))
    }

    /** Anahtar kare bayrağı ilk parçada gelmeyebilir; IDR NAL'ı (5) var mı bak. */
    private fun isKeyFrame(au: ByteArray): Boolean {
        var i = 0
        val lim = minOf(au.size - 4, 256)
        while (i < lim) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                if ((au[i + 3].toInt() and 0x1F) == 5) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** Göndericiden anahtar kare ister (en çok 200 ms'de bir). Herhangi bir iş parçacığından çağrılabilir. */
    fun requestKeyframe(force: Boolean = false) {
        val s = socket ?: return
        val t = now()
        if (!force && t - lastKeyframeRequestMs < 200) return
        lastKeyframeRequestMs = t
        val to = peer ?: return
        try {
            s.send(DatagramPacket(byteArrayOf(Proto.TYPE_KEYFRAME_REQUEST), 1, to))
        } catch (_: Exception) {
        }
    }

    private fun tickStats() {
        val t = now()
        if (t - statsAtMs < 1000) return
        val secs = (t - statsAtMs) / 1000.0
        val connected = lastPacketMs != 0L && t - lastPacketMs < 3000
        onStats((frames / secs).toInt(), (bytes * 8 / 1000 / secs).toInt(), connected)
        frames = 0
        bytes = 0
        statsAtMs = t
    }

    private fun now() = System.currentTimeMillis()
}
