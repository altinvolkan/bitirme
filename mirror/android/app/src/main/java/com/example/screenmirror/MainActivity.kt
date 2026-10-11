package com.example.screenmirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.example.screenmirror.monetization.AdsManager
import com.example.screenmirror.monetization.BillingManager
import com.example.screenmirror.monetization.Entitlements
import com.example.screenmirror.ui.C
import com.example.screenmirror.ui.ConnectingScreen
import com.example.screenmirror.ui.Device
import com.example.screenmirror.ui.Fonts
import com.example.screenmirror.ui.HomeScreen
import com.example.screenmirror.ui.Kind
import com.example.screenmirror.ui.LiveScreen
import com.example.screenmirror.ui.MATCH
import com.example.screenmirror.ui.NoDeviceScreen
import com.example.screenmirror.ui.OnboardingScreen
import com.example.screenmirror.ui.PairingScreen
import com.example.screenmirror.ui.PremiumScreen
import com.example.screenmirror.ui.Screen
import com.example.screenmirror.ui.SettingsScreen
import com.example.screenmirror.ui.UiPrefs

/**
 * Tek aktivite; ekranlar [Screen] sınıflarıdır (ui/ klasörü). Bu sınıf cihaz keşfi,
 * yansıtma başlatma akışı ve servis durum olaylarını yönetir.
 */
class MainActivity : ComponentActivity() {

    lateinit var prefs: UiPrefs
        private set
    lateinit var ads: AdsManager
        private set
    lateinit var billing: BillingManager
        private set

    /** Keşfedilen ve elle eklenen ekranlar. */
    val devices = ArrayList<Device>()
    var selected: Device? = null
        private set

    private val manual = ArrayList<Device>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private val stack = ArrayList<Screen>()
    private val current: Screen? get() = stack.lastOrNull()

    private lateinit var udpDiscovery: TvDiscovery
    private lateinit var airplayDiscovery: AirPlayDiscovery
    private var discovering = false
    private val emptyCheck = Runnable {
        if (devices.isEmpty() && current is HomeScreen && !MirrorService.running) show(NoDeviceScreen(this))
    }

    /** Ekran yakalama izninden sonra başlatılacak hedef. */
    private var pending: Device? = null

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val dev = pending ?: return@registerForActivityResult
        if (r.resultCode != Activity.RESULT_OK || r.data == null) {
            toast(getString(R.string.toast_no_capture))
            return@registerForActivityResult
        }
        val svc = Intent(this, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_RESULT_CODE, r.resultCode)
            .putExtra(MirrorService.EXTRA_DATA, r.data)
            .putExtra(MirrorService.EXTRA_LATENCY_MS, prefs.latencyMs)
            .putExtra(MirrorService.EXTRA_BITRATE_MBPS, prefs.bitrateMbps)
            .putExtra(MirrorService.EXTRA_AUDIO, prefs.audio)
        if (dev.kind == Kind.AIRPLAY) {
            svc.putExtra(MirrorService.EXTRA_MODE, MirrorService.MODE_AIRPLAY)
                .putExtra(MirrorService.EXTRA_TARGET, dev.airplay)
        } else {
            svc.putExtra(MirrorService.EXTRA_MODE, MirrorService.MODE_UDP)
                .putExtra(MirrorService.EXTRA_HOST, dev.ip)
                .putExtra(MirrorService.EXTRA_PORT, dev.port)
        }
        MirrorEvents.reset()
        startForegroundService(svc)
        if (dev.kind == Kind.AIRPLAY) {
            showFlow(ConnectingScreen(this, 0))
        } else {
            showFlow(LiveScreen(this))
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Ses yakalama izni; reddedilse bile görüntü sessiz olarak devam eder. */
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(getString(R.string.toast_no_audio))
        requestCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        Entitlements.load(this)
        prefs = UiPrefs(this)
        ads = AdsManager(this)
        billing = BillingManager(this) { current?.onPremiumChanged() }

        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        root = FrameLayout(this).apply {
            setBackgroundColor(C.BG)
            fitsSystemWindows = true
        }
        setContentView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                current?.onBack() ?: finish()
            }
        })

        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        udpDiscovery = TvDiscovery(this) { name, host, port ->
            if (host != null) addDevice(Device(Kind.UDP, name, getString(R.string.subtitle_own), host, port))
        }
        airplayDiscovery = AirPlayDiscovery(this) { t ->
            addDevice(Device(Kind.AIRPLAY, t.name, subtitleFor(t.txt), t.ip, t.port, t))
        }

        if (prefs.onboarded) show(HomeScreen(this)) else show(OnboardingScreen(this))
        billing.start()
        ads.start { current?.onPremiumChanged() }
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
        MirrorEvents.onStatus = { state, detail -> handleStatus(state, detail) }
        MirrorEvents.onPinRequired = { showPin() }
    }

    override fun onStop() {
        MirrorEvents.onStatus = null
        MirrorEvents.onPinRequired = null
        stopDiscovery()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        billing.refresh()
        syncWithService()
    }

    override fun onDestroy() {
        main.removeCallbacks(emptyCheck)
        ads.destroy()
        billing.end()
        super.onDestroy()
    }

    // --- ekran yığını ---

    fun show(screen: Screen, push: Boolean = true) {
        current?.onHidden()
        if (!push && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
        display(screen)
    }

    private fun display(screen: Screen) {
        screen.view = screen.build()
        root.removeAllViews()
        root.addView(screen.view, FrameLayout.LayoutParams(MATCH, MATCH))
        screen.onShown()
    }

    fun back() {
        if (stack.size <= 1) return finish()
        current?.onHidden()
        stack.removeAt(stack.lastIndex)
        display(stack.last())
    }

    fun goHome() {
        current?.onHidden()
        stack.clear()
        val home = HomeScreen(this)
        stack.add(home)
        display(home)
        scheduleEmptyCheck()
    }

    fun finishApp() = finish()

    private fun inFlow() = current is ConnectingScreen || current is PairingScreen || current is LiveScreen

    /** Bağlanma/eşleştirme/canlı akışındaki ekranı değiştirir (yığına eklemeden). */
    private fun showFlow(screen: Screen) = show(screen, push = !inFlow())

    fun openSettings() = show(SettingsScreen(this))
    fun openPremium() = show(PremiumScreen(this))
    fun openNoDevice() = show(NoDeviceScreen(this))
    fun openOnboarding() = show(OnboardingScreen(this, fromSettings = true))

    fun openPrivacy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
        } catch (_: Exception) {
            toast(getString(R.string.toast_privacy_fail))
        }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    // --- cihazlar ---

    private fun subtitleFor(txt: String): String {
        val model = txt.lineSequence().firstOrNull { it.startsWith("model=") }?.substringAfter('=') ?: ""
        return when {
            model.startsWith("AppleTV6,2") || model.startsWith("AppleTV11,1") || model.startsWith("AppleTV14,1") -> "Apple TV 4K"
            model.startsWith("AppleTV") -> "Apple TV"
            else -> getString(R.string.subtitle_airplay)
        }
    }

    private fun addDevice(d: Device) {
        if (devices.any { it.key == d.key }) return
        devices.add(d)
        if (selected == null || d.key == prefs.lastDeviceKey) selected = d
        current?.onDevicesChanged()
    }

    fun select(d: Device) {
        selected = d
        prefs.lastDeviceKey = d.key
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        udpDiscovery.start()
        if (AirPlayBackendLoader.available) airplayDiscovery.start()
        scheduleEmptyCheck()
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        udpDiscovery.stop()
        if (AirPlayBackendLoader.available) airplayDiscovery.stop()
        main.removeCallbacks(emptyCheck)
    }

    private fun scheduleEmptyCheck() {
        main.removeCallbacks(emptyCheck)
        if (devices.isEmpty()) main.postDelayed(emptyCheck, 8000)
    }

    fun rescan() {
        stopDiscovery()
        devices.clear()
        devices.addAll(manual)
        if (selected != null && devices.none { it.key == selected?.key }) selected = devices.firstOrNull()
        startDiscovery()
        goHome()
    }

    fun connectManual(ip: String) {
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) {
            toast(getString(R.string.toast_bad_ip))
            return
        }
        val d = if (AirPlayBackendLoader.available) {
            Device(Kind.AIRPLAY, ip, getString(R.string.subtitle_airplay), ip, 7000, AirPlayTarget(ip, ip, 7000, ""))
        } else {
            Device(Kind.UDP, ip, getString(R.string.subtitle_own), ip, Protocol.DEFAULT_PORT)
        }
        if (manual.none { it.key == d.key }) manual.add(d)
        if (devices.none { it.key == d.key }) devices.add(d)
        select(d)
        goHome()
    }

    // --- yansıtma akışı ---

    fun startMirror() {
        val d = selected
        if (d == null) {
            toast(getString(R.string.toast_pick_device))
            return
        }
        if (MirrorService.running) return syncWithService()
        pending = d
        val needAudio = d.kind == Kind.AIRPLAY && prefs.audio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needAudio) audioPermission.launch(Manifest.permission.RECORD_AUDIO) else requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mpm.createScreenCaptureIntent())
    }

    fun stopMirror() {
        startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
        goHome()
    }

    fun submitPin(pin: String) {
        MirrorService.instance?.submitPin(pin)
        showFlow(ConnectingScreen(this, 1))
    }

    fun cancelPin() {
        MirrorService.instance?.submitPin("")
        stopMirror()
    }

    private fun showPin() {
        if (current is PairingScreen) return
        showFlow(PairingScreen(this, LiveInfo.deviceName.ifEmpty { selected?.name ?: "Apple TV" }))
    }

    private fun handleStatus(state: String, detail: String) {
        when (state) {
            "connecting" -> stage(0)
            "pairing" -> stage(1)
            "setup" -> stage(2)
            "streaming" -> if (current !is LiveScreen) showFlow(LiveScreen(this))
            "audio" -> toast(detail)
            "error" -> {
                toast(getString(R.string.toast_connect_fail, detail))
                if (inFlow()) goHome()
            }
            "stopped" -> if (inFlow()) goHome()
        }
    }

    private fun stage(n: Int) {
        val c = current
        when {
            c is PairingScreen -> {} // kod girilene kadar bekle
            c is ConnectingScreen -> c.setStage(n)
            else -> showFlow(ConnectingScreen(this, n))
        }
    }

    /** Uygulama arka plandan dönünce arayüzü servisin gerçek durumuna getirir. */
    private fun syncWithService() {
        if (!MirrorService.running) {
            if (inFlow() && MirrorEvents.lastState != "") goHome()
            return
        }
        when {
            LiveInfo.startedAtMs > 0 -> if (current !is LiveScreen) showFlow(LiveScreen(this))
            MirrorEvents.pinPending -> showPin()
            current is ConnectingScreen || current is PairingScreen -> {}
            else -> showFlow(ConnectingScreen(this, 0))
        }
    }

    companion object {
        /** Play Console'da zorunlu; yayınlamadan önce gerçek adresle değiştir (docs/PRIVACY_POLICY.md). */
        const val PRIVACY_URL = "https://example.com/yansit/gizlilik"
    }
}
