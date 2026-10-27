package com.altinvolkan.imirror.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Android TV alıcısı: telefondaki iMirror'dan UDP ile gelen H.264 görüntüyü ve sesi oynatır.
 * Ekran açıkken `_scrmirror._udp` olarak ağda görünür; bağlanınca bekleme paneli kaybolur.
 */
class TvActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var panel: LinearLayout
    private lateinit var addressText: TextView
    private lateinit var statsText: TextView

    @Volatile private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0

    private lateinit var decoder: H264Decoder
    private lateinit var audio: AudioPlayer
    private lateinit var receiver: UdpReceiver
    private lateinit var nsd: NsdRegistrar
    private var multicastLock: WifiManager.MulticastLock? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        setContentView(root)

        audio = AudioPlayer()
        decoder = H264Decoder(
            surfaceProvider = { surface },
            onSize = { w, h -> runOnUiThread { fitVideo(w, h) } },
            onNeedKeyframe = { receiver.requestKeyframe() },
        )
        receiver = UdpReceiver(Proto.PORT, decoder, audio) { fps, kbps, connected ->
            runOnUiThread {
                panel.visibility = if (connected) View.GONE else View.VISIBLE
                statsText.visibility = if (connected) View.VISIBLE else View.GONE
                statsText.text = "$fps fps · $kbps kbps"
            }
        }
        nsd = NsdRegistrar(this)
    }

    override fun onStart() {
        super.onStart()
        hideSystemUi()
        startReceiving()
    }

    override fun onStop() {
        stopReceiving()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun startReceiving() {
        if (running) return
        running = true
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("imirror-tv")?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        val ip = NetInfo.localIpv4()
        addressText.text = if (ip == null) getString(R.string.tv_no_network)
        else getString(R.string.tv_address, "$ip:${Proto.PORT}")
        if (!receiver.start()) {
            addressText.text = getString(R.string.tv_port_error, Proto.PORT)
            return
        }
        audio.start()
        nsd.start(deviceName(), Proto.PORT)
    }

    private fun stopReceiving() {
        if (!running) return
        running = false
        nsd.stop()
        receiver.stop()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun deviceName(): String {
        val name = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        return "iMirror TV - " + (name?.takeIf { it.isNotBlank() } ?: Build.MODEL)
    }

    // --- arayüz ---

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                decoder.release()
            }
        })
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (videoW > 0) fitVideo(videoW, videoH)
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E1014"))
        }
        panel.addView(text(getString(R.string.app_name), 64f, Color.parseColor("#7CF5C8"), true))
        panel.addView(text(getString(R.string.tv_waiting), 40f, Color.parseColor("#F3F5F8"), true), margin(top = 28))
        panel.addView(text(getString(R.string.tv_hint), 26f, Color.parseColor("#A0A8B6"), false), margin(top = 16))
        addressText = text("", 28f, Color.parseColor("#F2C879"), true)
        panel.addView(addressText, margin(top = 40))
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))

        statsText = text("", 18f, Color.parseColor("#80FFFFFF"), false).apply {
            visibility = View.GONE
        }
        root.addView(
            statsText,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(16)
                rightMargin = dp(24)
            },
        )
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        gravity = Gravity.CENTER
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun margin(top: Int) = LinearLayout.LayoutParams(-2, -2).apply {
        topMargin = dp(top)
        leftMargin = dp(48)
        rightMargin = dp(48)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Görüntüyü kare oranını koruyarak ekrana sığdırır (siyah boşluklar kalır). */
    private fun fitVideo(w: Int, h: Int) {
        videoW = w
        videoH = h
        val rw = root.width
        val rh = root.height
        if (rw == 0 || rh == 0 || w == 0 || h == 0) return
        val scale = minOf(rw.toFloat() / w, rh.toFloat() / h)
        val lp = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
        surfaceView.layoutParams = lp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
