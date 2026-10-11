package com.alpdroid.app

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The companion AlpBrowser app (a separate APK, package [PACKAGE]) as seen from AlpDroid: is it
 * installed, at what version, how to open it, and how its GitHub releases are tagged. AlpBrowser
 * ships its own releases tagged `alpbrowser-vX.Y.Z` (see [TAG_PREFIX]); [AppUpdater] reuses this to
 * offer install/update from AlpDroid's Settings with the same download animation as the app updater.
 */
object BrowserInstall {
    const val PACKAGE = "com.alpdroid.browser"

    /** AlpBrowser release tags look like `alpbrowser-v0.1.0` — distinct from AlpDroid's `v1.7.69`. */
    const val TAG_PREFIX = "alpbrowser-"

    /** (versionName, versionCode) of the installed AlpBrowser, or null when it is not installed. */
    fun installedVersion(context: Context): Pair<String, Long>? = runCatching {
        val info = context.packageManager.getPackageInfo(PACKAGE, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        (info.versionName ?: "0") to code
    }.getOrNull()

    fun isInstalled(context: Context): Boolean = installedVersion(context) != null

    /** When AlpBrowser was last installed or updated (0 if it is not installed) — a cheap PackageManager lookup that
     *  does not start the app. A token/port file written before this moment may be stale. */
    fun lastUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0).lastUpdateTime
    }.getOrDefault(0L)

    /** A launch intent for AlpBrowser, or null when it is not installed / has no launcher activity. */
    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** Whether [tag] is an AlpBrowser release tag (not an AlpDroid one). */
    fun isBrowserTag(tag: String): Boolean = tag.startsWith(TAG_PREFIX)

    /** `alpbrowser-v0.1.0` -> `0.1.0`; tolerates a missing prefix or `v`. */
    fun versionFromTag(tag: String): String = tag.removePrefix(TAG_PREFIX).removePrefix("v")
}
