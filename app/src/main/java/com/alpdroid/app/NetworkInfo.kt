package com.alpdroid.app

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * `proot` never isolates the network namespace (unlike a real container runtime), so Alpine
 * processes already share the device's live network stack directly — same IP, same
 * interfaces, same reachability, no VPNService or root required. What Alpine's guest *doesn't*
 * get automatically is: (1) working DNS, because there's no netd running inside it to answer
 * its resolver, and (2) any indication of what IP other devices on the same network would use
 * to reach back in (for e.g. running sshd or a dev server inside the guest and hitting it from
 * a laptop on the same LAN). This object covers both.
 */
object NetworkInfo {
    /** Host DNS servers, for writing into the guest's /etc/resolv.conf. */
    fun dnsServers(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val props = cm.getLinkProperties(network) ?: return emptyList()
        return props.dnsServers.mapNotNull { it.hostAddress }
    }

    /** Non-loopback IPv4 addresses of this device, for display ("reach this shell over LAN at…"). */
    fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
            .toList()
    }.getOrDefault(emptyList())
}
