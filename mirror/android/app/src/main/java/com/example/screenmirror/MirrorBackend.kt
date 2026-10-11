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

    fun status(state: String, detail: String) {
        main.post { onStatus?.invoke(state, detail) }
    }

    fun pinRequired() {
        main.post { onPinRequired?.invoke() }
    }
}

object AirPlayCredentials {
    private const val PREFS = "airplay_creds"

    fun load(ctx: Context, deviceId: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(deviceId, "") ?: ""

    fun save(ctx: Context, deviceId: String, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(deviceId, json).apply()
    }
}
