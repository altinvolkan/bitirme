package com.example.screenmirror.cast

import android.util.Log
import com.example.screenmirror.FrameSink
import com.example.screenmirror.MirrorBackend
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Google Cast "Chrome Mirroring" alıcı uygulamasına (appId 0F5096E8) Cast Streaming ile yansıtma.
 * Akış (Chromium components/mirroring ile aynı sıra):
 *  1. TLS 8009 -> CONNECT (receiver-0) -> LAUNCH 0F5096E8 -> RECEIVER_STATUS (transportId)
 *  2. CONNECT (transportId) -> OFFER (urn:x-cast:com.google.cast.webrtc) -> ANSWER (udpPort, ssrcs, sendIndexes)
 *  3. Şifreli Cast RTP akışı UDP ile; RTCP geri bildirimi aynı soketten gelir.
 *
 * Her adım `ScreenMirrorCast` etiketiyle günlüğe yazılır; alıcı beklenmedik cevap verirse günlük sorunu gösterir.
 */
class CastBackend(
    private val target: CastTarget,
    private val cb: MirrorBackend.Callbacks,
    private val maxBitrate: Int,
    private val fps: Int,
) : MirrorBackend {

    @Volatile private var latencyMs = 400
    @Volatile private var audioEnabled = true

    private var channel: CastChannel? = null
    private var udp: DatagramSocket? = null
    private var video: CastStream? = null
    private var audio: CastStream? = null
    private var opus: OpusEncoder? = null
    private val stopLatch = CountDownLatch(1)
    @Volatile private var stopped = false
    private val inbox = LinkedBlockingQueue<Triple<String, String, JSONObject>>()
    private var requestId = 1
    private var t0Ns = 0L
    private var audioBaseRtp = -1L
    private var transportId = ""
    private var sessionId = ""

    override val sink: FrameSink = object : FrameSink {
        override fun sendFrame(data: ByteArray, size: Int, keyframe: Boolean) {
            val v = video ?: return
            val now = System.nanoTime()
            v.sendFrame(data.copyOf(size), keyframe, (now - t0Ns) * VIDEO_TIMEBASE / 1_000_000_000L, now)
        }

        override val wantsAudio: Boolean get() = audio != null && audioEnabled
        override val audioSampleRate: Int get() = OpusEncoder.SAMPLE_RATE

        override fun sendAudio(pcm: ByteArray, size: Int) {
            opus?.feed(pcm, size)
        }

        override fun close() = stop()
    }

    override fun setAudioEnabled(on: Boolean) {
        audioEnabled = on
    }

    override fun setLatencyMs(ms: Int) {
        latencyMs = ms.coerceIn(100, 1000)
    }

    override fun submitPin(pin: String) {}

    override fun run() {
        try {
            session()
            stopLatch.await()
        } catch (e: Exception) {
            Log.w(TAG, "Cast oturumu başarısız", e)
            if (!stopped) cb.onStatus("error", e.message ?: e.javaClass.simpleName)
        } finally {
            cleanup()
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        stopLatch.countDown()
    }

    // --- oturum ---

    private fun session() {
        cb.onStatus("connecting", target.name)
        val ch = CastChannel(
            target.ip, target.port,
            onMessage = { src, ns, payload -> onMessage(src, ns, payload) },
            onClosed = { reason ->
                Log.w(TAG, "kanal kapandı: $reason")
                if (!stopped) {
                    cb.onStatus("error", "Bağlantı koptu: $reason")
                    stop()
                }
            },
        )
        channel = ch
        ch.connect()
        ch.send(CastChannel.RECEIVER_ID, CastChannel.NS_CONNECTION, connectJson())
        startHeartbeat(ch)

        // 1) yansıtma uygulamasını başlat
        val launchId = requestId++
        ch.send(
            CastChannel.RECEIVER_ID, CastChannel.NS_RECEIVER,
            JSONObject().put("type", "LAUNCH").put("appId", MIRRORING_APP_ID).put("requestId", launchId).toString(),
        )
        val status = waitFor(15_000, "alıcı uygulaması başlamadı") { _, ns, j ->
            if (ns != CastChannel.NS_RECEIVER) return@waitFor false
            when (j.optString("type")) {
                "LAUNCH_ERROR", "INVALID_REQUEST" ->
                    throw IllegalStateException("Cihaz yansıtma uygulamasını açmadı (${j.optString("reason", j.optString("type"))})")
                "RECEIVER_STATUS" -> findApp(j) != null
                else -> false
            }
        }
        val app = findApp(status.third)!!
        transportId = app.getString("transportId")
        sessionId = app.optString("sessionId")
        cb.onStatus("pairing", "")

        // 2) uygulamaya bağlan, OFFER gönder
        ch.send(transportId, CastChannel.NS_CONNECTION, connectJson())
        cb.onStatus("setup", "")

        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val withAudio = audioEnabled && OpusEncoder.isAvailable()
        val audioSsrc = 1 + random.nextInt(500_000)
        val videoSsrc = 500_001 + random.nextInt(500_000)
        val maxW = 1920
        val maxH = 1080
        val streams = JSONArray()
        var idx = 0
        val audioIndex = if (withAudio) idx++ else -1
        if (withAudio) streams.put(audioStreamJson(audioIndex, audioSsrc, key, iv))
        val videoIndex = idx++
        streams.put(videoStreamJson(videoIndex, videoSsrc, key, iv, maxW, maxH))

        val seq = requestId++
        val offer = JSONObject()
            .put("type", "OFFER").put("seqNum", seq)
            .put(
                "offer",
                JSONObject().put("castMode", "mirroring").put("receiverGetStatus", true).put("supportedStreams", streams),
            )
        ch.send(transportId, CastChannel.NS_WEBRTC, offer.toString())

        val answerMsg = waitFor(15_000, "alıcı OFFER'a cevap vermedi") { _, ns, j ->
            ns == CastChannel.NS_WEBRTC && j.optString("type").equals("ANSWER", true) && j.optInt("seqNum", -1) == seq
        }.third
        if (answerMsg.optString("result") != "ok") {
            throw IllegalStateException("Alıcı teklifi reddetti: ${answerMsg.optJSONObject("error") ?: answerMsg}")
        }
        val answer = answerMsg.getJSONObject("answer")
        val udpPort = answer.getInt("udpPort")
        val sendIdx = answer.getJSONArray("sendIndexes")
        val ssrcs = answer.getJSONArray("ssrcs")
        if (sendIdx.length() != ssrcs.length()) throw IllegalStateException("ANSWER ssrc sayısı tutmuyor")

        // 3) akışları kur
        val sock = DatagramSocket()
        sock.sendBufferSize = 1 shl 20
        udp = sock
        val dest = InetSocketAddress(target.ip, udpPort)
        t0Ns = System.nanoTime()
        for (i in 0 until sendIdx.length()) {
            val si = sendIdx.getInt(i)
            val recvSsrc = ssrcs.getInt(i)
            when (si) {
                videoIndex -> video = CastStream(
                    sock, dest, videoSsrc, recvSsrc, VIDEO_PAYLOAD_TYPE, VIDEO_TIMEBASE, key, iv, latencyMs,
                    onPli = { cb.onNeedKeyframe() }, tag = "video",
                )
                audioIndex -> if (audioIndex >= 0) {
                    audio = CastStream(
                        sock, dest, audioSsrc, recvSsrc, AUDIO_PAYLOAD_TYPE, OpusEncoder.SAMPLE_RATE, key, iv, latencyMs,
                        onPli = {}, tag = "audio",
                    )
                }
            }
        }
        if (video == null) throw IllegalStateException("Alıcı video akışını seçmedi")
        audio?.let { a ->
            try {
                opus = OpusEncoder(AUDIO_BITRATE) { pkt, ptsUs -> onOpus(a, pkt, ptsUs) }
            } catch (e: Exception) {
                Log.w(TAG, "Opus kodlayıcı açılamadı, ses kapalı", e)
                audio = null
            }
        }
        startRtcpThreads(sock)

        // tuval: alıcının kabul ettiği en büyük çözünürlük (yoksa 1920x1080)
        var cw = maxW
        var ch2 = maxH
        answer.optJSONObject("constraints")?.optJSONObject("video")?.optJSONObject("maxDimensions")?.let {
            val w = it.optInt("width", 0)
            val h = it.optInt("height", 0)
            if (w >= 320 && h >= 180) {
                cw = minOf(w, maxW)
                ch2 = minOf(h, maxH)
            }
        }
        Log.i(TAG, "ANSWER tamam: udpPort=$udpPort video=${video != null} audio=${audio != null} tuval=${cw}x$ch2 cevap=$answer")
        cb.onCanvas(cw, ch2)
        cb.onStatus("streaming", target.name)
    }

    private fun onOpus(a: CastStream, pkt: ByteArray, ptsUs: Long) {
        val now = System.nanoTime()
        if (audioBaseRtp < 0) audioBaseRtp = (now - t0Ns) * OpusEncoder.SAMPLE_RATE / 1_000_000_000L
        val rtp = audioBaseRtp + ptsUs * OpusEncoder.SAMPLE_RATE / 1_000_000L
        val refNs = t0Ns + rtp * 1_000_000_000L / OpusEncoder.SAMPLE_RATE
        a.sendFrame(pkt, true, rtp, refNs)
    }

    // --- mesajlar ---

    private fun onMessage(source: String, ns: String, payload: String) {
        val j = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return
        }
        if (ns == CastChannel.NS_HEARTBEAT) {
            if (j.optString("type") == "PING") {
                try {
                    channel?.send(CastChannel.RECEIVER_ID, CastChannel.NS_HEARTBEAT, "{\"type\":\"PONG\"}")
                } catch (_: Exception) {
                }
            }
            lastHeartbeatMs = System.currentTimeMillis()
            return
        }
        if (ns == CastChannel.NS_CONNECTION && j.optString("type") == "CLOSE" && source == transportId && transportId.isNotEmpty()) {
            if (!stopped) cb.onStatus("stopped", "")
            stop()
            return
        }
        // Alıcı uygulaması kapandıysa (ör. TV'den çıkıldı) RECEIVER_STATUS içinde artık görünmez
        if (ns == CastChannel.NS_RECEIVER && j.optString("type") == "RECEIVER_STATUS" &&
            transportId.isNotEmpty() && video != null && findApp(j) == null
        ) {
            if (!stopped) cb.onStatus("stopped", "")
            stop()
            return
        }
        inbox.offer(Triple(source, ns, j))
    }

    @Volatile private var lastHeartbeatMs = System.currentTimeMillis()

    private fun waitFor(
        timeoutMs: Long,
        timeoutMessage: String,
        pred: (String, String, JSONObject) -> Boolean,
    ): Triple<String, String, JSONObject> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!stopped) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val m = inbox.poll(left, TimeUnit.MILLISECONDS) ?: break
            if (pred(m.first, m.second, m.third)) return m
        }
        if (stopped) throw IllegalStateException("durduruldu")
        throw IllegalStateException(timeoutMessage)
    }

    private fun findApp(status: JSONObject): JSONObject? {
        val apps = status.optJSONObject("status")?.optJSONArray("applications") ?: return null
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            if (a.optString("appId") == MIRRORING_APP_ID && a.optString("transportId").isNotEmpty()) return a
        }
        return null
    }

    private fun connectJson() = JSONObject()
        .put("type", "CONNECT")
        .put("origin", JSONObject())
        .put("userAgent", "iMirror")
        .put(
            "senderInfo",
            JSONObject().put("sdkType", 2).put("version", "15.605.1.3").put("browserVersion", "44.0.2403.30")
                .put("platform", 4).put("systemVersion", "Android").put("connectionType", 1),
        )
        .toString()

    private fun startHeartbeat(ch: CastChannel) {
        thread(name = "cast-heartbeat", isDaemon = true) {
            while (!stopped) {
                try {
                    Thread.sleep(5000)
                    ch.send(CastChannel.RECEIVER_ID, CastChannel.NS_HEARTBEAT, "{\"type\":\"PING\"}")
                    if (System.currentTimeMillis() - lastHeartbeatMs > 25_000) {
                        if (!stopped) cb.onStatus("error", "Cihaz yanıt vermiyor")
                        stop()
                        return@thread
                    }
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: Exception) {
                    return@thread
                }
            }
        }
    }

    // --- akış yardımcıları ---

    private fun startRtcpThreads(sock: DatagramSocket) {
        // Alıcıdan gelen RTCP
        thread(name = "cast-rtcp-rx", isDaemon = true) {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            sock.soTimeout = 500
            while (!stopped) {
                try {
                    p.setData(buf, 0, buf.size)
                    sock.receive(p)
                    video?.onRtcp(buf, p.length)
                    audio?.onRtcp(buf, p.length)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!stopped) return@thread
                }
            }
        }
        // Her 500 ms'de gönderici raporu (lip-sync)
        thread(name = "cast-rtcp-sr", isDaemon = true) {
            while (!stopped) {
                try {
                    Thread.sleep(RTCP_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                video?.sendSenderReport()
                audio?.sendSenderReport()
            }
        }
    }

    private fun videoStreamJson(index: Int, ssrc: Int, key: ByteArray, iv: ByteArray, w: Int, h: Int) = JSONObject()
        .put("index", index)
        .put("type", "video_source")
        .put("codecName", "h264")
        .put("rtpProfile", "cast")
        .put("rtpPayloadType", VIDEO_PAYLOAD_TYPE)
        .put("ssrc", ssrc)
        .put("targetDelay", latencyMs)
        .put("aesKey", hex(key))
        .put("aesIvMask", hex(iv))
        .put("timeBase", "1/$VIDEO_TIMEBASE")
        .put("receiverRtcpEventLog", true)
        .put("rtpExtensions", "adaptive_playout_delay")
        .put("renderMode", "video")
        .put("maxFrameRate", "${fps * 1000}/1000")
        .put("maxBitRate", maxBitrate)
        .put("resolutions", JSONArray().put(JSONObject().put("width", w).put("height", h)))

    private fun audioStreamJson(index: Int, ssrc: Int, key: ByteArray, iv: ByteArray) = JSONObject()
        .put("index", index)
        .put("type", "audio_source")
        .put("codecName", "opus")
        .put("rtpProfile", "cast")
        .put("rtpPayloadType", AUDIO_PAYLOAD_TYPE)
        .put("ssrc", ssrc)
        .put("targetDelay", latencyMs)
        .put("aesKey", hex(key))
        .put("aesIvMask", hex(iv))
        .put("timeBase", "1/${OpusEncoder.SAMPLE_RATE}")
        .put("receiverRtcpEventLog", true)
        .put("rtpExtensions", "adaptive_playout_delay")
        .put("bitRate", AUDIO_BITRATE)
        .put("sampleRate", OpusEncoder.SAMPLE_RATE)
        .put("channels", 2)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    private fun cleanup() {
        stopped = true
        try {
            if (sessionId.isNotEmpty()) {
                // Alıcıdaki yansıtma uygulamasını kapat
                channel?.send(
                    CastChannel.RECEIVER_ID, CastChannel.NS_RECEIVER,
                    JSONObject().put("type", "STOP").put("sessionId", sessionId).put("requestId", requestId++).toString(),
                )
            }
        } catch (_: Exception) {
        }
        opus?.close()
        opus = null
        udp?.close()
        udp = null
        channel?.close()
        channel = null
        video = null
        audio = null
    }

    companion object {
        private const val TAG = "ScreenMirrorCast"
        /** Chrome'un "Chrome Mirroring" alıcı uygulaması. */
        private const val MIRRORING_APP_ID = "0F5096E8"
        private const val VIDEO_PAYLOAD_TYPE = 96
        private const val AUDIO_PAYLOAD_TYPE = 127
        private const val VIDEO_TIMEBASE = 90000
        private const val AUDIO_BITRATE = 96_000
        private const val RTCP_INTERVAL_MS = 500L
    }
}
