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

    data class Usb(val name: String, val id: String, val title: String, val kind: String, val hasPermission: Boolean, val device: UsbDevice)

    data class Iface(val name: String, val up: Boolean, val addresses: List<String>, val kind: String)

    data class Wifi(val ssid: String, val level: Int, val secured: Boolean)

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
        return runCatching { removableDrives(context) }.getOrDefault(emptyList())
            .filter { it.mounted }
            .mapNotNull { d -> d.path?.let { it to "/mnt/${d.mountName}" } }
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
            )
        }
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

    fun interfaces(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filterNot { it.isLoopback }.map { n ->
            Iface(
                name = n.name,
                up = runCatching { n.isUp }.getOrDefault(false),
                addresses = n.inetAddresses.toList().mapNotNull { it.hostAddress?.substringBefore('%') },
                kind = when {
                    n.name.startsWith("wlan") -> "Wi-Fi"
                    n.name.startsWith("eth") -> "Ethernet"
                    n.name.startsWith("rndis") || n.name.startsWith("usb") -> "USB tether"
                    n.name.startsWith("rmnet") || n.name.startsWith("ccmni") -> "Mobile data"
                    n.name.startsWith("tun") || n.name.startsWith("wg") -> "VPN"
                    else -> "Other"
                },
            )
        }
    }.getOrDefault(emptyList())

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last scan results the system has (Android throttles app-triggered scans to a few per
     *  couple of minutes, so this may be slightly stale) — needs location permission. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun wifiScan(context: Context): List<Wifi> {
        if (!hasLocationPermission(context)) return emptyList()
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        runCatching { wm.startScan() }
        return runCatching {
            wm.scanResults.filter { it.SSID.isNotBlank() }
                .sortedByDescending { it.level }
                .distinctBy { it.SSID }
                .map { Wifi(it.SSID, it.level, it.capabilities.contains("WPA") || it.capabilities.contains("WEP")) }
        }.getOrDefault(emptyList())
    }
}
