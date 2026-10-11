package com.alpdroid.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Hands the AlpDroid app the control-API connection details — the token and the actually-bound control/proxy ports —
 * so AlpDroid can wire up `browserctl` for the agent without the user copying the token by hand. Protected by a
 * SIGNATURE-level permission in the manifest, so only an app signed with the same key (AlpDroid) can read it; no other
 * app on the device can obtain the token this way.
 *
 * Queried with `contentResolver.call(authority, "info", ...)`, returning a Bundle of token / controlPort / proxyPort.
 */
class BrowserControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "info") return null
        val app = context?.applicationContext as? AlpBrowserApp ?: return null
        return Bundle().apply {
            putString("token", app.controlToken)
            putInt("controlPort", app.bridge.boundPort.takeIf { it > 0 } ?: AlpBrowserApp.CONTROL_PORT)
            putInt("proxyPort", app.browserEngine.boundPort.takeIf { it > 0 } ?: AlpBrowserApp.PROXY_PORT)
        }
    }

    // No table-style access — everything goes through call().
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.alpdroid.browser.control"
    }
}
