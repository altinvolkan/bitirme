package com.example.screenmirror

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** Bonjour/mDNS ile Apple TV'deki alıcıyı bulur. Callback'ler main thread'de çağrılır. */
@Suppress("DEPRECATION")
class TvDiscovery(
    context: Context,
    /** [platform]: alıcının duyurduğu "platform" özelliği ("androidtv" gibi); yoksa null. */
    private val onFound: (name: String, host: String?, port: Int, platform: String?) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        try {
            nsd.stopServiceDiscovery(discoveryListener)
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
                // IPv6 link-local adresler gönderim için sorunlu; sadece IPv4 kabul et
                val v4 = (info.host as? Inet4Address)?.hostAddress
                val platform = info.attributes["platform"]?.toString(Charsets.UTF_8)
                main.post { onFound(info.serviceName, v4, info.port, platform) }
            }
        })
    }
}
