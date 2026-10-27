package com.altinvolkan.imirror.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Bu TV'yi ağda `_scrmirror._udp` olarak duyurur; telefon uygulaması listede gösterir. */
class NsdRegistrar(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Proto.SERVICE_TYPE
            setPort(port)
            setAttribute("platform", "androidtv")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd.unregisterService(l)
        } catch (_: Exception) {
        }
    }
}
