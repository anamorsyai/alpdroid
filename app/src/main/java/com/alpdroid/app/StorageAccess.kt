package com.alpdroid.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * "All files access" is what makes the shared workspace actually visible outside this app —
 * without it, scoped storage (API 30+) confines the app to its own
 * Android/data/com.alpdroid.app/ directory, invisible to the Files app, a USB/MTP transfer,
 * or any other app's file picker. This is exactly the grant Termux itself asks for.
 */
object StorageAccess {
    /** On API 30+, MANAGE_EXTERNAL_STORAGE (a special, Settings-granted permission, not a runtime
     *  prompt) is the real check. Below that, WRITE_EXTERNAL_STORAGE is a normal dangerous
     *  permission that still needs an explicit runtime grant on API 23-29 despite being declared
     *  in the manifest — this used to report "granted" unconditionally on the whole API 24-29
     *  range without ever actually checking (or requesting) it, so the "Grant shared-storage
     *  access" banner never appeared, /sdcard got bind-mounted into the guest unreadable, and any
     *  backup silently failed writing to a shared-storage path with no permission behind it. */
    fun isGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true // below M, storage permissions are granted at install time, not at runtime
    }

    /** Device's shared storage root (e.g. /storage/emulated/0) — what gets bind-mounted into Alpine. */
    fun sharedStorageRoot(): java.io.File = Environment.getExternalStorageDirectory()

    fun requestIntent(context: Context): Intent {
        val uri = Uri.parse("package:${context.packageName}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }
    }
}
