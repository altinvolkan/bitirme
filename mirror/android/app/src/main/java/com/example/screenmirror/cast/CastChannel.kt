package com.example.screenmirror.cast

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Cast V2 kontrol kanalı: TLS (8009) üzerinde "4 bayt uzunluk + CastMessage (protobuf)" çerçeveleri.
 * CastMessage alanları (cast_channel.proto): 1 protocol_version(enum, 0), 2 source_id, 3 destination_id,
 * 4 namespace, 5 payload_type(0 = STRING), 6 payload_utf8, 7 payload_binary. Protobuf elle kodlanır.
 */
class CastChannel(
    private val host: String,
    private val port: Int,
    private val onMessage: (source: String, namespace: String, payload: String) -> Unit,
    private val onClosed: (reason: String) -> Unit,
) {
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    @Volatile private var closed = false

    /**
     * Cast cihazları cihaz sertifikasıyla (kendinden imzalı) yanıt verir; Chrome da dahil herkes
     * ana makine adı doğrulaması yapmaz. Gönderici olarak alıcıyı doğrulamıyoruz.
     */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun connect(timeoutMs: Int = 8000) {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = 0
        s.startHandshake()
        socket = s
        out = s.outputStream
        thread(name = "cast-channel-rx", isDaemon = true) { readLoop(DataInputStream(s.inputStream)) }
    }

    fun send(destination: String, namespace: String, json: String, source: String = SENDER_ID) {
        val body = encode(source, destination, namespace, json)
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size ushr 24).toByte()
        frame[1] = (body.size ushr 16).toByte()
        frame[2] = (body.size ushr 8).toByte()
        frame[3] = body.size.toByte()
        System.arraycopy(body, 0, frame, 4, body.size)
        synchronized(writeLock) {
            val o = out ?: throw IllegalStateException("Cast kanalı kapalı")
            o.write(frame)
            o.flush()
        }
        if (namespace != NS_HEARTBEAT) Log.d(TAG, "→ $destination $namespace $json")
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun readLoop(input: DataInputStream) {
        var reason = "bağlantı kapandı"
        try {
            while (!closed) {
                val len = input.readInt()
                if (len <= 0 || len > MAX_MESSAGE) {
                    reason = "geçersiz mesaj uzunluğu $len"
                    break
                }
                val buf = ByteArray(len)
                input.readFully(buf)
                val m = decode(buf) ?: continue
                if (m.namespace != NS_HEARTBEAT) Log.d(TAG, "← ${m.source} ${m.namespace} ${m.payload}")
                onMessage(m.source, m.namespace, m.payload)
            }
        } catch (e: Exception) {
            if (!closed) reason = e.message ?: e.toString()
        }
        if (!closed) {
            closed = true
            onClosed(reason)
        }
    }

    // --- protobuf ---

    private class Decoded(val source: String, val namespace: String, val payload: String)

    private fun encode(source: String, dest: String, ns: String, payload: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x08); o.write(0) // protocol_version = CASTV2_1_0
        writeString(o, 0x12, source)
        writeString(o, 0x1A, dest)
        writeString(o, 0x22, ns)
        o.write(0x28); o.write(0) // payload_type = STRING
        writeString(o, 0x32, payload)
        return o.toByteArray()
    }

    private fun writeString(o: ByteArrayOutputStream, tag: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write(tag)
        var n = b.size
        while (n >= 0x80) {
            o.write((n and 0x7F) or 0x80)
            n = n ushr 7
        }
        o.write(n)
        o.write(b)
    }

    private fun decode(b: ByteArray): Decoded? {
        var i = 0
        var source = ""
        var ns = ""
        var payload = ""
        fun varint(): Long {
            var shift = 0
            var v = 0L
            while (i < b.size) {
                val c = b[i++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if ((c and 0x80) == 0) return v
                shift += 7
            }
            return -1
        }
        while (i < b.size) {
            val key = varint().toInt()
            if (key < 0) return null
            val field = key ushr 3
            when (key and 7) {
                0 -> varint()
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || i + len > b.size) return null
                    val s = String(b, i, len, Charsets.UTF_8)
                    when (field) {
                        2 -> source = s
                        4 -> ns = s
                        6 -> payload = s
                    }
                    i += len
                }
                1 -> i += 8
                5 -> i += 4
                else -> return null
            }
        }
        return Decoded(source, ns, payload)
    }

    companion object {
        private const val TAG = "ScreenMirrorCast"
        private const val MAX_MESSAGE = 1 shl 20
        const val SENDER_ID = "sender-0"
        const val RECEIVER_ID = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_WEBRTC = "urn:x-cast:com.google.cast.webrtc"
    }
}
