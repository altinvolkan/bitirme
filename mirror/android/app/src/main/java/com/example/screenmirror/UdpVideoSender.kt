package com.example.screenmirror

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** Encoded kareleri parçalayıp UDP ile gönderir; geri kanaldan keyframe isteğini dinler. */
class UdpVideoSender(
    host: String,
    port: Int,
    private val onKeyframeRequest: () -> Unit,
) : FrameSink {

    private val socket = DatagramSocket().apply {
        sendBufferSize = 1 shl 20
        connect(InetAddress.getByName(host), port)
    }
    private val buf = ByteArray(Protocol.MTU)
    private val packet = DatagramPacket(buf, buf.size)
    private var frameId = 0

    init {
        thread(name = "udp-rx", isDaemon = true) {
            val rx = DatagramPacket(ByteArray(64), 64)
            while (!socket.isClosed) {
                try {
                    socket.receive(rx)
                    if (rx.length >= 1 && rx.data[0] == Protocol.TYPE_KEYFRAME_REQUEST) onKeyframeRequest()
                } catch (_: IOException) {
                    // PortUnreachable vb.: busy-loop olmasın
                    if (!socket.isClosed) Thread.sleep(50)
                }
            }
        }
    }

    /** Sadece encoder thread'inden çağrılır. */
    override fun sendFrame(data: ByteArray, size: Int, keyframe: Boolean) {
        val fragCount = (size + Protocol.MAX_PAYLOAD - 1) / Protocol.MAX_PAYLOAD
        val id = frameId++
        for (i in 0 until fragCount) {
            val off = i * Protocol.MAX_PAYLOAD
            val len = minOf(Protocol.MAX_PAYLOAD, size - off)
            buf[0] = Protocol.TYPE_VIDEO
            buf[1] = if (keyframe) Protocol.FLAG_KEYFRAME else 0
            buf[2] = (id ushr 24).toByte()
            buf[3] = (id ushr 16).toByte()
            buf[4] = (id ushr 8).toByte()
            buf[5] = id.toByte()
            buf[6] = (i ushr 8).toByte()
            buf[7] = i.toByte()
            buf[8] = (fragCount ushr 8).toByte()
            buf[9] = fragCount.toByte()
            System.arraycopy(data, off, buf, Protocol.HEADER_SIZE, len)
            packet.setData(buf, 0, Protocol.HEADER_SIZE + len)
            try {
                socket.send(packet)
            } catch (_: IOException) {
                // alıcı yoksa sessizce düş
            }
        }
    }

    override fun close() {
        socket.close()
    }
}
