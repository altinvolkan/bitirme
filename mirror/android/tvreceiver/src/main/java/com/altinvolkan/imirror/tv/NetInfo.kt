package com.altinvolkan.imirror.tv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetInfo {
    /** Yerel IPv4 (telefondan elle bağlanmak için ekranda gösterilir); yoksa null. */
    fun localIpv4(): String? {
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return null
        }
        val candidates = ifaces
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
            .filter { it.second.isSiteLocalAddress }
        // Ethernet/Wi-Fi öncelikli
        return (candidates.firstOrNull { it.first.startsWith("eth") || it.first.startsWith("wlan") }
            ?: candidates.firstOrNull())?.second?.hostAddress
    }
}
