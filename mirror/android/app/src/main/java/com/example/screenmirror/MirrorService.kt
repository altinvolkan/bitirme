package com.example.screenmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import androidx.core.content.IntentCompat
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * İki mod:
 *  - MODE_UDP: kendi tvOS alıcımıza UDP ile.
 *  - MODE_AIRPLAY: doğrudan Apple TV'nin AirPlay alıcısına (airplay.aar gerekir).
 */
class MirrorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var sink: FrameSink? = null
    private var backend: MirrorBackend? = null
    private var audio: AudioCapturer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var audioMuted = false
    private var bitrateBps = DEFAULT_BITRATE_MBPS * 1_000_000

    /** AirPlay modunda alıcının tuvali; 0 ise UDP modu. */
    private var canvasW = 0
    private var canvasH = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) main.post { applyScreenSize() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForegroundWithNotification()
        MirrorEvents.reset()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            // Android 14+: createVirtualDisplay'den önce callback kaydı zorunlu
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, main)
            projection = proj

            // Wi-Fi güç tasarrufu paketleri toplu/geç gönderip ses-görüntü takılmasına yol açar
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "screenmirror")
                .also { it.setReferenceCounted(false); it.acquire() }

            audioMuted = !intent.getBooleanExtra(EXTRA_AUDIO, true)
            bitrateBps = intent.getIntExtra(EXTRA_BITRATE_MBPS, DEFAULT_BITRATE_MBPS) * 1_000_000
            val ok = when (intent.getStringExtra(EXTRA_MODE)) {
                MODE_AIRPLAY -> startAirPlay(proj, intent)
                else -> startUdp(proj, intent)
            }
            if (!ok) {
                stopSelf()
                return START_NOT_STICKY
            }
            instance = this
            running = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // --- UDP (kendi tvOS alıcımız) ---

    private fun startUdp(proj: MediaProjection, intent: Intent): Boolean {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return false
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        val (w, h) = targetSize()
        LiveInfo.reset(host, MODE_UDP, 0, 0, 30)
        LiveInfo.width = w
        LiveInfo.height = h
        LiveInfo.startedAtMs = SystemClock.elapsedRealtime()
        MirrorEvents.status("streaming", host)

        val enc = arrayOfNulls<ScreenEncoder>(1)
        val snd = UdpVideoSender(host, port) { enc[0]?.requestKeyFrame() }
        sink = snd
        enc[0] = ScreenEncoder(proj, w, h, resources.displayMetrics.densityDpi, snd)
        encoder = enc[0]
        enc[0]!!.start()
        watchRotation()
        return true
    }

    // --- AirPlay (doğrudan Apple TV) ---

    private fun startAirPlay(proj: MediaProjection, intent: Intent): Boolean {
        val target = IntentCompat.getSerializableExtra(intent, EXTRA_TARGET, AirPlayTarget::class.java)
            ?: return false
        LiveInfo.reset(
            target.name, MODE_AIRPLAY, bitrateBps / 1_000_000,
            intent.getIntExtra(EXTRA_LATENCY_MS, DEFAULT_LATENCY_MS), AIRPLAY_FPS,
        )

        val cb = object : MirrorBackend.Callbacks {
            override fun onCanvas(width: Int, height: Int) {
                main.post { startAirPlayEncoder(proj, width, height) }
            }

            override fun onNeedKeyframe() {
                encoder?.requestKeyFrame()
            }

            override fun onStatus(state: String, detail: String) {
                if (state == "streaming" && LiveInfo.startedAtMs == 0L) {
                    LiveInfo.startedAtMs = SystemClock.elapsedRealtime()
                }
                MirrorEvents.status(state, detail)
                if (state == "error" || state == "stopped") main.post { stopSelf() }
            }

            override fun onPinRequired() = MirrorEvents.pinRequired()

            override fun onCredentials(deviceId: String, json: String) {
                AirPlayCredentials.save(applicationContext, deviceId, json)
            }
        }

        val b = AirPlayBackendLoader.create(target, AirPlayCredentials.load(this, target.deviceId), cb)
        if (b == null) {
            MirrorEvents.status("error", "AirPlay kütüphanesi (airplay.aar) yok; mirror/airplay-go/README.md'ye bak")
            return false
        }
        backend = b
        sink = b.sink
        b.setAudioEnabled(hasAudioPermission() && intent.getBooleanExtra(EXTRA_AUDIO, true))
        b.setLatencyMs(intent.getIntExtra(EXTRA_LATENCY_MS, DEFAULT_LATENCY_MS))
        thread(name = "airplay-session", isDaemon = true) { b.run() }
        return true
    }

    private fun startAirPlayEncoder(proj: MediaProjection, w: Int, h: Int) {
        if (encoder != null || backend == null) return
        canvasW = w
        canvasH = h
        val (ew, eh) = targetSize()
        LiveInfo.width = ew
        LiveInfo.height = eh
        encoder = ScreenEncoder(
            proj, ew, eh, resources.displayMetrics.densityDpi, sink!!,
            bitrate = bitrateBps, fps = AIRPLAY_FPS,
        ).also { it.start() }
        watchRotation()

        val s = sink!!
        if (s.wantsAudio) {
            val capturer = AudioCapturer(proj, s)
            if (capturer.start()) {
                capturer.muted = audioMuted
                audio = capturer
            } else {
                MirrorEvents.status("audio", "Ses yakalanamadı; görüntü sessiz devam ediyor")
            }
        }
    }

    // --- Ortak ---

    /** Ekranın o anki (döndürmeye göre) piksel boyutu. */
    private fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        var w = display.mode.physicalWidth
        var h = display.mode.physicalHeight
        if (display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270) {
            w = h.also { h = w }
        }
        return w to h
    }

    /** Encoder için hedef boyut: AirPlay'de alıcı tuvaline, UDP'de MAX_SIDE'a sığdır. */
    private fun targetSize(): Pair<Int, Int> {
        val (sw, sh) = screenSize()
        val maxW = if (canvasW > 0) canvasW else MAX_SIDE
        val maxH = if (canvasH > 0) canvasH else MAX_SIDE
        val scale = min(maxW.toFloat() / sw, maxH.toFloat() / sh).let { if (canvasW > 0) it else min(1f, it) }
        return alignedSize((sw * scale).toInt(), (sh * scale).toInt())
    }

    /** Encoder'lar genelde 16'nın katını sever. */
    private fun alignedSize(w: Int, h: Int) = max(16, w and 0xF.inv()) to max(16, h and 0xF.inv())

    private fun watchRotation() {
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
    }

    /** Ekran döndü: encoder'ı yeni yönün boyutuna al. */
    private fun applyScreenSize() {
        val enc = encoder ?: return
        val (w, h) = targetSize()
        if (w != enc.currentWidth || h != enc.currentHeight) {
            enc.resize(w, h)
            enc.requestKeyFrame()
            LiveInfo.width = w
            LiveInfo.height = h
        }
    }

    private fun hasAudioPermission() =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Canlı yayında sesi kapat/aç (akış kesilmez). */
    fun setAudioMuted(muted: Boolean) {
        audioMuted = muted
        audio?.muted = muted
    }

    fun submitPin(pin: String) {
        backend?.submitPin(pin)
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ekran yansıtma", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ekran Apple TV'ye yansıtılıyor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Durdur", stopIntent).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        if (MirrorEvents.lastState != "error") MirrorEvents.status("stopped", "")
        running = false
        instance = null
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        audio?.stop()
        audio = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        encoder?.stop()
        backend?.stop()
        sink?.close()
        projection?.stop()
        encoder = null
        backend = null
        sink = null
        projection = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.screenmirror.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TARGET = "target"
        const val EXTRA_LATENCY_MS = "latencyMs"
        const val EXTRA_BITRATE_MBPS = "bitrateMbps"
        const val EXTRA_AUDIO = "audio"
        const val DEFAULT_BITRATE_MBPS = 8
        const val DEFAULT_LATENCY_MS = 250
        const val MODE_UDP = "udp"
        const val MODE_AIRPLAY = "airplay"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIF_ID = 1
        private const val MAX_SIDE = 1280 // UDP modunda uzun kenar; 720p sınıfı => düşük gecikme
        private const val AIRPLAY_FPS = 30

        @Volatile
        var running = false
            private set

        @Volatile
        var instance: MirrorService? = null
            private set
    }
}
