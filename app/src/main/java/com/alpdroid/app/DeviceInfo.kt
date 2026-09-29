package com.alpdroid.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import java.io.File
import java.net.NetworkInterface

/**
 * Read-only views of what's plugged into / available to the phone, for the Devices panel — and
 * the removable-drive list [AlpineSession] bind-mounts into the guest. Everything here works
 * without root: drives come from Android's own StorageManager (only filesystems the phone's own
 * Android can mount ever show up), USB devices from the USB host API, interfaces from java.net.
 */
object DeviceInfo {
    const val ACTION_USB_PERMISSION = "com.alpdroid.app.USB_PERMISSION"

    data class Drive(val label: String, val uuid: String?, val path: File?, val mounted: Boolean, val totalBytes: Long, val freeBytes: Long) {
        /** Guest mount name: /mnt/<this>. Sanitized — labels are arbitrary user text. */
        val mountName: String get() = (label.ifBlank { uuid ?: "drive" }).replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    data class Usb(val name: String, val id: String, val title: String, val kind: String, val hasPermission: Boolean, val device: UsbDevice, val details: List<Pair<String, String>>)

    data class Iface(val name: String, val up: Boolean, val addresses: List<String>, val kind: String, val mtu: Int, val rxBytes: Long, val txBytes: Long)

    data class Wifi(val ssid: String, val level: Int, val security: String, val band: String, val channel: Int, val connected: Boolean)

    data class ActiveNet(val transport: String, val validated: Boolean, val metered: Boolean, val dns: List<String>, val gateway: String?, val downKbps: Int, val upKbps: Int)

    data class WifiLink(val ssid: String, val rssi: Int, val speedMbps: Int, val band: String)

    fun removableDrives(context: Context): List<Drive> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.storageVolumes.filter { it.isRemovable && !it.isPrimary }.map { v ->
            val path = volumePath(v)
            val mounted = v.state == android.os.Environment.MEDIA_MOUNTED && path?.isDirectory == true
            val stat = if (mounted) runCatching { StatFs(path!!.path) }.getOrNull() else null
            Drive(
                label = v.getDescription(context) ?: "Removable drive",
                uuid = v.uuid,
                path = path,
                mounted = mounted,
                totalBytes = stat?.totalBytes ?: 0L,
                freeBytes = stat?.availableBytes ?: 0L,
            )
        }
    }

    private fun volumePath(v: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory
        else v.uuid?.let { File("/storage/$it") }

    /** Host path -> guest path pairs for [AlpineSession]'s proot `-b` arguments. Only drives that
     *  are actually mounted, and only when all-files access exists (without it the guest couldn't
     *  read them anyway). */
    fun guestBinds(context: Context): List<Pair<File, String>> {
        if (!StorageAccess.isGranted(context)) return emptyList()
        val drives = runCatching { removableDrives(context) }.getOrDefault(emptyList()).filter { it.mounted }
        val paths = guestPaths(drives)
        return drives.mapNotNull { d -> d.path?.let { it to (paths[d] ?: "/mnt/${d.mountName}") } }
    }

    /**
     * Guest path per drive, de-duplicated: two volumes can sanitize to the same name
     * ("My Drive" vs "My_Drive"), and the second bind would otherwise shadow the first
     * under one /mnt/<name>. Suffixes (-2, -3, …) in volume order, deterministic per set.
     */
    fun guestPaths(drives: List<Drive>): Map<Drive, String> {
        val used = mutableSetOf<String>()
        return drives.associateWith { d ->
            var name = d.mountName
            var n = 1
            while (!used.add(name)) {
                n++
                name = "${d.mountName}-$n"
            }
            "/mnt/$name"
        }
    }

    fun usbDevices(context: Context): List<Usb> {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return um.deviceList.values.map { d ->
            Usb(
                name = d.deviceName,
                id = "%04x:%04x".format(d.vendorId, d.productId),
                title = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device" },
                kind = usbKind(d),
                hasPermission = um.hasPermission(d),
                device = d,
                details = usbDetails(d, um.hasPermission(d)),
            )
        }
    }

    private fun usbDetails(d: UsbDevice, permitted: Boolean): List<Pair<String, String>> = buildList {
        add("IDs" to "%04x:%04x".format(d.vendorId, d.productId))
        add("Class" to "0x%02x".format(d.deviceClass))
        add("Interfaces" to "${d.interfaceCount}")
        var endpoints = 0
        for (i in 0 until d.interfaceCount) endpoints += d.getInterface(i).endpointCount
        add("Endpoints" to "$endpoints")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) d.version?.let { add("USB version" to it) }
        if (permitted) runCatching { d.serialNumber }.getOrNull()?.let { add("Serial" to it) }
        add("Node" to d.deviceName)
    }

    private fun usbKind(d: UsbDevice): String {
        val classes = buildSet {
            add(d.deviceClass)
            for (i in 0 until d.interfaceCount) add(d.getInterface(i).interfaceClass)
        }
        return when {
            8 in classes -> "Mass storage"
            2 in classes || 10 in classes -> "Serial / network (CDC)"
            9 in classes -> "Hub"
            3 in classes -> "Input (HID)"
            0xE0 in classes -> "Wireless (Wi-Fi / Bluetooth)"
            255 in classes -> "Vendor-specific (adapter?)"
            else -> "Other"
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    fun requestUsbPermission(context: Context, device: UsbDevice) {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        um.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags))
    }

    private fun stat(name: String, key: String): Long =
        runCatching { File("/sys/class/net/$name/statistics/$key").readText().trim().toLong() }.getOrDefault(-1L)

    fun interfaces(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filterNot { it.isLoopback }.map { n ->
            val addrs = n.inetAddresses.toList().mapNotNull { it.hostAddress?.substringBefore('%') }
            Iface(
                name = n.name,
                up = runCatching { n.isUp }.getOrDefault(false),
                addresses = addrs.sortedBy { it.contains(':') },
                kind = when {
                    n.name.startsWith("wlan") -> "Wi-Fi"
                    n.name.startsWith("eth") -> "Ethernet"
                    n.name.startsWith("rndis") || n.name.startsWith("usb") -> "USB tether"
                    n.name.startsWith("rmnet") || n.name.startsWith("ccmni") -> "Mobile data"
                    n.name.startsWith("tun") || n.name.startsWith("wg") -> "VPN"
                    n.name.startsWith("dummy") -> "Virtual"
                    n.name.startsWith("p2p") -> "Wi-Fi Direct"
                    else -> "Other"
                },
                mtu = runCatching { n.mtu }.getOrDefault(0),
                rxBytes = stat(n.name, "rx_bytes"),
                txBytes = stat(n.name, "tx_bytes"),
            )
        }
    }.getOrDefault(emptyList())

    fun activeNetwork(context: Context): ActiveNet? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net)
        val lp = cm.getLinkProperties(net)
        val transport = when {
            caps == null -> "Unknown"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Other"
        }
        ActiveNet(
            transport = transport,
            validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = !(caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: true),
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
            gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress,
            downKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            upKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
        )
    }.getOrNull()

    private fun bandOf(freqMhz: Int) = when {
        freqMhz in 2400..2500 -> "2.4 GHz"
        freqMhz in 4900..5900 -> "5 GHz"
        freqMhz in 5925..7125 -> "6 GHz"
        else -> "?"
    }

    private fun channelOf(freqMhz: Int) = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5000..5900 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> 0
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    fun wifiLink(context: Context): WifiLink? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return null
        if (info.networkId == -1) return null
        val ssid = info.ssid.removeSurrounding("\"").takeUnless { it == "<unknown ssid>" } ?: "(name hidden — needs location on)"
        WifiLink(ssid, info.rssi, info.linkSpeed, bandOf(info.frequency))
    }.getOrNull()

    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else runCatching { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) }.getOrDefault(true)
    }

    fun deviceSummary(context: Context): List<Pair<String, String>> {
        val data = StatFs(android.os.Environment.getDataDirectory().path)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = data.totalBytes - data.availableBytes
        return listOf(
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "CPU ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Kernel" to (System.getProperty("os.version") ?: "?"),
            "RAM" to "${humanBytes(mem.availMem)} free of ${humanBytes(mem.totalMem)}",
            "Internal storage" to "${humanBytes(data.availableBytes)} free of ${humanBytes(data.totalBytes)} (${used * 100 / data.totalBytes.coerceAtLeast(1)}% used)",
        )
    }

    fun humanBytes(b: Long): String = com.alpdroid.app.files.FileOps.humanSize(b)

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last scan results the system has (Android throttles app-triggered scans to a few per
     *  couple of minutes, so this may be slightly stale) — needs location permission. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun wifiScan(context: Context, rescan: Boolean = true): List<Wifi> {
        if (!hasLocationPermission(context)) return emptyList()
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (rescan) runCatching { wm.startScan() } // throttled by Android, so only when asked
        val connected = wifiLink(context)?.ssid
        return runCatching {
            wm.scanResults.filter { it.SSID.isNotBlank() }
                .sortedByDescending { it.level }
                .distinctBy { it.SSID }
                .map {
                    val c = it.capabilities
                    Wifi(
                        ssid = it.SSID,
                        level = it.level,
                        security = when {
                            c.contains("SAE") -> "WPA3"
                            c.contains("WPA2") -> "WPA2"
                            c.contains("WPA") -> "WPA"
                            c.contains("WEP") -> "WEP"
                            else -> "Open"
                        },
                        band = bandOf(it.frequency),
                        channel = channelOf(it.frequency),
                        connected = it.SSID == connected,
                    )
                }
        }.getOrDefault(emptyList())
    }
}
