package com.example.screenmirror

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.Serializable
import java.net.Inet4Address

/** Bonjour ile bulunan bir AirPlay alıcısı (Apple TV vb.). */
data class AirPlayTarget(
    val name: String,
    val ip: String,
    val port: Int,
    /** "anahtar=değer" satırları; Go tarafı özellik bitlerini buradan okur. */
    val txt: String,
) : Serializable {
    val deviceId: String
        get() = txt.lineSequence().firstOrNull { it.startsWith("deviceid=") }?.substringAfter('=') ?: ip

    override fun toString() = "$name ($ip)"
}

/** `_airplay._tcp` servislerini bulur. Callback'ler main thread'de çağrılır. */
@Suppress("DEPRECATION")
class AirPlayDiscovery(
    context: Context,
    private val onFound: (AirPlayTarget) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    main.postDelayed({ resolve(info) }, 500)
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val ip = (info.host as? Inet4Address)?.hostAddress ?: return
                val txt = info.attributes.entries.joinToString("\n") { (k, v) ->
                    "$k=${v?.toString(Charsets.UTF_8) ?: ""}"
                }
                main.post { onFound(AirPlayTarget(info.serviceName, ip, info.port, txt)) }
            }
        })
    }
}
