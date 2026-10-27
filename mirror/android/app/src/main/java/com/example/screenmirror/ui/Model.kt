package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.content.Context
import com.example.screenmirror.AirPlayTarget

enum class Kind { AIRPLAY, UDP, CAST }

/** Listede gösterilen bir hedef ekran. */
data class Device(
    val kind: Kind,
    val name: String,
    val subtitle: String,
    val ip: String,
    val port: Int,
    val airplay: AirPlayTarget? = null,
    val cast: com.example.screenmirror.cast.CastTarget? = null,
) {
    val key: String get() = "${kind.name}:$ip"
}

/** Kullanıcının seçtikleri (kalıcı). */
class UiPrefs(context: Context) {
    private val sp = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var latencyIdx: Int
        get() = sp.getInt("latency_idx", 1).coerceIn(0, LATENCIES.lastIndex)
        set(v) {
            sp.edit().putInt("latency_idx", v).apply()
        }

    var bitrateIdx: Int
        get() {
            // Eski sürümde kaydedilmiş geçersiz bir seçim varsa varsayılana (8 Mbps) dön
            val v = sp.getInt("bitrate_idx", 1)
            return if (v in 0..BITRATES.lastIndex) v else 1
        }
        set(v) {
            sp.edit().putInt("bitrate_idx", v).apply()
        }

    /** Kare hızı seçimi: FPS_OPTIONS dizinine; varsayılan 30 (daha az ısı). */
    var fpsIdx: Int
        get() = sp.getInt("fps_idx", 0).coerceIn(0, FPS_OPTIONS.lastIndex)
        set(v) {
            sp.edit().putInt("fps_idx", v).apply()
        }

    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) {
            sp.edit().putBoolean("audio", v).apply()
        }

    var lastDeviceKey: String?
        get() = sp.getString("last_device", null)
        set(v) {
            sp.edit().putString("last_device", v).apply()
        }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) {
            sp.edit().putBoolean("onboarded", v).apply()
        }

    val latencyMs: Int get() = LATENCIES[latencyIdx].second
    val bitrateMbps: Int get() = BITRATES[bitrateIdx]
    /** Fiilen kullanılacak kare hızı: 60 fps yalnızca premium'da; abonelik bitmişse 30'a döner. */
    val fps: Int get() = if (com.example.screenmirror.monetization.Entitlements.premium) FPS_OPTIONS[fpsIdx] else FPS_OPTIONS[0]

    companion object {
        val LATENCIES = listOf(R.string.latency_low to 120, R.string.latency_balanced to 250, R.string.latency_stable to 400)
        val BITRATES = listOf(4, 8, 12)
        val FPS_OPTIONS = listOf(30, 60)
    }
}

abstract class Screen(protected val a: com.example.screenmirror.MainActivity) {
    lateinit var view: android.view.View

    abstract fun build(): android.view.View

    protected fun dp(v: Int): Int = a.dp(v)

    protected fun str(id: Int, vararg args: Any): String = a.getString(id, *args)

    open fun onShown() {}
    open fun onHidden() {}
    open fun onBack() {
        a.back()
    }

    open fun onPremiumChanged() {}
    open fun onDevicesChanged() {}
}
