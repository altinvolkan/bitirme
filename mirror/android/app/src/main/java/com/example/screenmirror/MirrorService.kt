package com.example.screenmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForegroundWithNotification()

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
        val (w, h) = scaledSize(MAX_SIDE, MAX_SIDE)

        val enc = arrayOfNulls<ScreenEncoder>(1)
        val snd = UdpVideoSender(host, port) { enc[0]?.requestKeyFrame() }
        sink = snd
        enc[0] = ScreenEncoder(proj, w, h, resources.displayMetrics.densityDpi, snd)
        encoder = enc[0]
        enc[0]!!.start()
        return true
    }

    // --- AirPlay (doğrudan Apple TV) ---

    private fun startAirPlay(proj: MediaProjection, intent: Intent): Boolean {
        val target = IntentCompat.getSerializableExtra(intent, EXTRA_TARGET, AirPlayTarget::class.java)
            ?: return false

        val cb = object : MirrorBackend.Callbacks {
            override fun onCanvas(width: Int, height: Int) {
                main.post { startAirPlayEncoder(proj, width, height) }
            }

            override fun onNeedKeyframe() {
                encoder?.requestKeyFrame()
            }

            override fun onStatus(state: String, detail: String) {
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
        thread(name = "airplay-session", isDaemon = true) { b.run() }
        return true
    }

    private fun startAirPlayEncoder(proj: MediaProjection, canvasW: Int, canvasH: Int) {
        if (encoder != null || backend == null) return
        val screen = screenSize()
        // Telefon ekranını alıcının tuvaline en-boy oranını koruyarak sığdır
        val scale = min(canvasW.toFloat() / screen.first, canvasH.toFloat() / screen.second)
        val (w, h) = alignedSize((screen.first * scale).toInt(), (screen.second * scale).toInt())
        encoder = ScreenEncoder(
            proj, w, h, resources.displayMetrics.densityDpi, sink!!,
            bitrate = AIRPLAY_BITRATE, fps = AIRPLAY_FPS,
        ).also { it.start() }
    }

    // --- Ortak ---

    private fun screenSize(): Pair<Int, Int> {
        val bounds = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun scaledSize(maxW: Int, maxH: Int): Pair<Int, Int> {
        val (sw, sh) = screenSize()
        val scale = min(1f, min(maxW.toFloat() / sw, maxH.toFloat() / sh))
        return alignedSize((sw * scale).toInt(), (sh * scale).toInt())
    }

    /** Encoder'lar genelde 16'nın katını sever. */
    private fun alignedSize(w: Int, h: Int) = max(16, w and 0xF.inv()) to max(16, h and 0xF.inv())

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
        running = false
        instance = null
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
        const val MODE_UDP = "udp"
        const val MODE_AIRPLAY = "airplay"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIF_ID = 1
        private const val MAX_SIDE = 1280 // UDP modunda uzun kenar; 720p sınıfı => düşük gecikme
        private const val AIRPLAY_BITRATE = 8_000_000
        private const val AIRPLAY_FPS = 30

        @Volatile
        var running = false
            private set

        @Volatile
        var instance: MirrorService? = null
            private set
    }
}
