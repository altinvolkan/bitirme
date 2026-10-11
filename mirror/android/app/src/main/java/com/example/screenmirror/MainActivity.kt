package com.example.screenmirror

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.FrameLayout
import com.example.screenmirror.monetization.AdsManager
import com.example.screenmirror.monetization.BillingManager
import com.example.screenmirror.monetization.Entitlements

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var premiumButton: Button
    private lateinit var bannerContainer: FrameLayout
    private lateinit var ads: AdsManager
    private lateinit var billing: BillingManager
    private lateinit var hostField: EditText
    private lateinit var udpButton: Button
    private lateinit var airplayButton: Button
    private lateinit var airplaySpinner: Spinner
    private val airplayTargets = ArrayList<AirPlayTarget>()
    private lateinit var airplayAdapter: ArrayAdapter<AirPlayTarget>
    private var port = Protocol.DEFAULT_PORT
    private lateinit var udpDiscovery: TvDiscovery
    private lateinit var airplayDiscovery: AirPlayDiscovery

    /** Ekran yakalama izninden sonra hangi modun başlatılacağı. */
    private var pendingMode = MirrorService.MODE_UDP

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK || r.data == null) {
            status.text = "Ekran yakalama izni verilmedi"
            refreshButtons()
            return@registerForActivityResult
        }
        val svc = Intent(this, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_RESULT_CODE, r.resultCode)
            .putExtra(MirrorService.EXTRA_DATA, r.data)
            .putExtra(MirrorService.EXTRA_MODE, pendingMode)
        if (pendingMode == MirrorService.MODE_AIRPLAY) {
            val target = airplaySpinner.selectedItem as AirPlayTarget
            svc.putExtra(MirrorService.EXTRA_TARGET, target)
            status.text = "AirPlay: ${target.name} bağlanıyor…"
        } else {
            val host = hostField.text.toString().trim()
            svc.putExtra(MirrorService.EXTRA_HOST, host).putExtra(MirrorService.EXTRA_PORT, port)
            status.text = "Yansıtılıyor → $host:$port"
        }
        startForegroundService(svc)
        refreshButtons()
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Ses yakalama izni; reddedilse bile görüntü sessiz olarak devam eder. */
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) status.text = "Ses izni verilmedi; görüntü sessiz yansıtılacak"
            requestCapture()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Entitlements.load(this)
        ads = AdsManager(this)
        billing = BillingManager(this) { refreshPremiumUi() }

        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val gap = (12 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply {
            textSize = 16f
            text = "Apple TV aranıyor…"
        }
        root.addView(status)

        // --- AirPlay (doğrudan Apple TV) ---
        val airplayLabel = TextView(this).apply {
            textSize = 14f
            setPadding(0, gap * 2, 0, 0)
            text = if (AirPlayBackendLoader.available) "AirPlay (Apple TV'ye doğrudan)"
            else "AirPlay kapalı: airplay.aar yok (mirror/airplay-go/README.md)"
        }
        airplayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, airplayTargets)
        airplaySpinner = Spinner(this).apply { adapter = airplayAdapter }
        airplayButton = Button(this).apply { setOnClickListener { onAirPlayButton() } }
        root.addView(airplayLabel)
        if (AirPlayBackendLoader.available) {
            root.addView(airplaySpinner)
            root.addView(airplayButton)
        }

        // --- Kendi tvOS alıcımız (UDP) ---
        val udpLabel = TextView(this).apply {
            textSize = 14f
            setPadding(0, gap * 2, 0, 0)
            text = "Kendi tvOS alıcımız (UDP)"
        }
        hostField = EditText(this).apply {
            hint = "Apple TV IP (otomatik dolar, gerekirse elle gir)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        udpButton = Button(this).apply { setOnClickListener { onUdpButton() } }
        root.addView(udpLabel)
        root.addView(hostField)
        root.addView(udpButton)

        // --- Reklamsız abonelik ---
        premiumButton = Button(this).apply {
            setPadding(0, gap, 0, gap)
            setOnClickListener {
                if (Entitlements.premium) billing.manageSubscription(this@MainActivity)
                else billing.launchPurchase(this@MainActivity)
            }
        }
        val restore = Button(this).apply {
            text = "Satın alımı geri yükle"
            setOnClickListener { billing.refresh() }
        }
        root.addView(premiumButton)
        root.addView(restore)

        // Banner alanı en altta
        bannerContainer = FrameLayout(this)
        val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(bannerContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(frame)

        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        udpDiscovery = TvDiscovery(this) { name, host, p ->
            port = p
            if (host != null) {
                hostField.setText(host)
                status.text = "Bulundu: $name ($host:$p)"
            } else {
                status.text = "Bulundu: $name ama IPv4 çözülemedi; TV ekranındaki IP'yi elle gir"
            }
        }
        airplayDiscovery = AirPlayDiscovery(this) { t ->
            if (airplayTargets.none { it.ip == t.ip }) {
                airplayTargets.add(t)
                airplayAdapter.notifyDataSetChanged()
                refreshButtons()
            }
        }
        refreshButtons()
        refreshPremiumUi()
        billing.start()
        ads.start { refreshPremiumUi() }
    }

    override fun onStart() {
        super.onStart()
        udpDiscovery.start()
        if (AirPlayBackendLoader.available) airplayDiscovery.start()
        MirrorEvents.onStatus = { state, detail -> showAirPlayState(state, detail) }
        MirrorEvents.onPinRequired = { askPin() }
    }

    override fun onStop() {
        MirrorEvents.onStatus = null
        MirrorEvents.onPinRequired = null
        udpDiscovery.stop()
        if (AirPlayBackendLoader.available) airplayDiscovery.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshButtons()
        billing.refresh()
    }

    override fun onDestroy() {
        ads.destroy()
        billing.end()
        super.onDestroy()
    }

    /** Premium durumuna göre banner ve abonelik düğmesini günceller. */
    private fun refreshPremiumUi() {
        if (Entitlements.premium) {
            ads.hideBanner(bannerContainer)
            premiumButton.text = "Premium aktif ✓ (aboneliği yönet)"
        } else {
            ads.showBanner(bannerContainer)
            val price = billing.priceText?.let { " – $it/ay" } ?: ""
            premiumButton.text = "Reklamsız kullan$price"
        }
    }

    private fun onUdpButton() {
        if (MirrorService.running) return stopService()
        if (hostField.text.isNullOrBlank()) {
            status.text = "Önce Apple TV IP'sini gir veya bulunmasını bekle"
            return
        }
        pendingMode = MirrorService.MODE_UDP
        ads.showInterstitialThen { requestCapture() }
    }

    private fun onAirPlayButton() {
        if (MirrorService.running) return stopService()
        if (airplayTargets.isEmpty()) {
            status.text = "AirPlay cihazı bulunamadı; aynı Wi-Fi'da mısın?"
            return
        }
        pendingMode = MirrorService.MODE_AIRPLAY
        ads.showInterstitialThen {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                requestCapture()
            } else {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopService() {
        startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
        status.text = "Durduruldu"
        udpButton.postDelayed({ refreshButtons() }, 300)
    }

    private fun showAirPlayState(state: String, detail: String) {
        status.text = when (state) {
            "connecting" -> "AirPlay: bağlanıyor… $detail"
            "pairing" -> "AirPlay: eşleştiriliyor…"
            "setup" -> "AirPlay: yayın kuruluyor…"
            "streaming" -> "AirPlay: yansıtılıyor → $detail"
            "audio" -> "AirPlay: yansıtılıyor (ses: $detail)"
            "error" -> "AirPlay hatası: $detail"
            else -> "Durduruldu"
        }
        refreshButtons()
    }

    /** Apple TV ekranında gösterilen PIN'i kullanıcıdan alır. */
    private fun askPin() {
        val input = EditText(this).apply {
            hint = "Apple TV'deki kod"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Eşleştirme kodu")
            .setMessage("Apple TV ekranında görünen kodu gir")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Tamam") { _, _ -> MirrorService.instance?.submitPin(input.text.toString().trim()) }
            .setNegativeButton("İptal") { _, _ -> MirrorService.instance?.submitPin("") }
            .show()
    }

    private fun refreshButtons() {
        val running = MirrorService.running
        udpButton.text = if (running) "Durdur" else "Yansıtmayı başlat (UDP)"
        airplayButton.text = if (running) "Durdur" else "AirPlay ile yansıt"
        val hasAirPlay = airplayTargets.isNotEmpty()
        airplayButton.isEnabled = running || hasAirPlay
        airplaySpinner.visibility = if (hasAirPlay) View.VISIBLE else View.GONE
    }
}
