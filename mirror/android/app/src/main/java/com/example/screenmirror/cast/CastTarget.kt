package com.example.screenmirror.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.Serializable
import java.net.Inet4Address

/** Bonjour ile bulunan bir Google Cast alıcısı (Android TV, Chromecast, Google TV, Cast destekli TV). */
data class CastTarget(
    val name: String,
    val ip: String,
    val port: Int,
    /** TXT `md`: model adı (ör. "Chromecast", "Google TV Streamer"). */
    val model: String,
    /** TXT `id`: cihaz kimliği. */
    val id: String,
) : Serializable

/**
 * `_googlecast._tcp` servislerini bulur. TXT kayıtları: `fn` = kullanıcı adı, `md` = model, `id` = kimlik,
 * `ca` = yetenek bit maskesi (bit 0 = video çıkışı; yalnızca ses olan hoparlörler listelenmez).
 * Callback'ler main thread'de çağrılır.
 */
@Suppress("DEPRECATION")
class CastDiscovery(
    context: Context,
    private val onFound: (CastTarget) -> Unit,
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
        try {
            nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: Exception) {
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
                fun txt(key: String) = info.attributes[key]?.toString(Charsets.UTF_8) ?: ""
                val caps = txt("ca").toIntOrNull() ?: 1
                val model = txt("md")
                // Yalnızca ses (hoparlör) ve çoklu bölge grupları görüntü alamaz
                if ((caps and 1) == 0 || model.startsWith("Google Cast Group")) return
                val name = txt("fn").ifEmpty { info.serviceName }
                main.post { onFound(CastTarget(name, ip, info.port.takeIf { it > 0 } ?: 8009, model, txt("id"))) }
            }
        })
    }
}
