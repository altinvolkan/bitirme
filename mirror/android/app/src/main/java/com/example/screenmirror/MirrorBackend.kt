package com.example.screenmirror

import android.content.Context

/**
 * AirPlay arka ucu. Gerçek uygulaması `src/airplay` kaynak setinde (airplay.aar varsa
 * derlenir); yoksa [AirPlayBackendLoader.create] null döner ve UDP modu çalışmaya devam eder.
 */
interface MirrorBackend {
    interface Callbacks {
        /** Alıcının tuvali belli oldu: encoder'ı bu tuvale sığacak şekilde başlat. */
        fun onCanvas(width: Int, height: Int)
        fun onNeedKeyframe()
        fun onStatus(state: String, detail: String)
        fun onPinRequired()
        fun onCredentials(deviceId: String, json: String)
    }

    val sink: FrameSink

    /** Bloklar; ayrı thread'de çağır. */
    fun run()
    fun submitPin(pin: String)
    fun setAudioEnabled(on: Boolean)

    /** Alıcı tampon payı (ms). Wi-Fi için 200+ önerilir. */
    fun setLatencyMs(ms: Int)
    fun stop()
}

object AirPlayBackendLoader {
    private const val CLASS_NAME = "com.example.screenmirror.airplay.AirPlayBackend"

    val available: Boolean
        get() = try {
            Class.forName(CLASS_NAME)
            true
        } catch (_: Throwable) {
            false
        }

    /** airplay.aar'ın sürüm etiketi; kütüphane yoksa "yok". */
    fun version(): String = try {
        Class.forName(CLASS_NAME).getMethod("version").invoke(null) as String
    } catch (_: Throwable) {
        "yok"
    }

    fun create(target: AirPlayTarget, savedCreds: String, cb: MirrorBackend.Callbacks): MirrorBackend? = try {
        Class.forName(CLASS_NAME)
            .getConstructor(AirPlayTarget::class.java, String::class.java, MirrorBackend.Callbacks::class.java)
            .newInstance(target, savedCreds, cb) as MirrorBackend
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    }
}

/** Servisten arayüze durum bildirimi (aynı process, main thread'e postalanır). */
object MirrorEvents {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile var onStatus: ((state: String, detail: String) -> Unit)? = null
    @Volatile var onPinRequired: (() -> Unit)? = null

    /** Arayüz yokken (arka planda) gelen son durum; ekran geri gelince buradan senkronize olunur. */
    @Volatile var lastState: String = ""
    @Volatile var lastDetail: String = ""
    @Volatile var pinPending: Boolean = false

    fun reset() {
        lastState = ""
        lastDetail = ""
        pinPending = false
    }

    fun status(state: String, detail: String) {
        lastState = state
        lastDetail = detail
        if (state != "pairing") pinPending = pinPending && state == "pairing"
        main.post { onStatus?.invoke(state, detail) }
    }

    fun pinRequired() {
        pinPending = true
        main.post { onPinRequired?.invoke() }
    }
}

/** Canlı yayın ekranının gösterdiği bilgiler (servis yazar, arayüz okur). */
object LiveInfo {
    @Volatile var deviceName: String = ""
    @Volatile var mode: String = ""
    @Volatile var startedAtMs: Long = 0L
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var fps: Int = 0
    @Volatile var bitrateMbps: Int = 0
    @Volatile var latencyMs: Int = 0

    fun reset(deviceName: String, mode: String, bitrateMbps: Int, latencyMs: Int, fps: Int) {
        this.deviceName = deviceName
        this.mode = mode
        this.bitrateMbps = bitrateMbps
        this.latencyMs = latencyMs
        this.fps = fps
        width = 0
        height = 0
        startedAtMs = 0L
    }
}

object AirPlayCredentials {
    private const val PREFS = "airplay_creds"

    fun load(ctx: Context, deviceId: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(deviceId, "") ?: ""

    fun save(ctx: Context, deviceId: String, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(deviceId, json).apply()
    }

    fun count(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.size

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
