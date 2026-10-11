package com.alpdroid.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android may stop AlpBrowser when it is not on screen. A foreground service keeps the process important, but phones
 * from several makers still end it unless the app is exempt from battery optimisation, which is why the control API can vanish
 * while another app is in front.
 */
object BatteryExemption {
    fun isExempt(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** The system's one-tap "let this app run in the background" dialog. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** The general battery-optimisation list, for turning the exemption off again or when the dialog is unavailable. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
