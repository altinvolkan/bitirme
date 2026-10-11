package com.example.screenmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import kotlin.math.max
import kotlin.math.min

class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var sender: UdpVideoSender? = null

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
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        if (data == null || host == null) {
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
            }, Handler(Looper.getMainLooper()))
            projection = proj

            val bounds = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            val (w, h) = scaledSize(bounds.width(), bounds.height(), MAX_SIDE)

            val enc = arrayOfNulls<ScreenEncoder>(1)
            val snd = UdpVideoSender(host, port) { enc[0]?.requestKeyFrame() }
            sender = snd
            enc[0] = ScreenEncoder(proj, w, h, resources.displayMetrics.densityDpi, snd)
            encoder = enc[0]
            enc[0]!!.start()
            running = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun scaledSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        // Encoder'lar genelde 16'nın katını sever
        val sw = max(16, (w * scale).toInt() and 0xF.inv())
        val sh = max(16, (h * scale).toInt() and 0xF.inv())
        return sw to sh
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ekran yansıtma", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
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
        encoder?.stop()
        sender?.close()
        projection?.stop()
        encoder = null
        sender = null
        projection = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.screenmirror.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIF_ID = 1
        private const val MAX_SIDE = 1280 // uzun kenar; 720p sınıfı => düşük gecikme

        @Volatile
        var running = false
            private set
    }
}
