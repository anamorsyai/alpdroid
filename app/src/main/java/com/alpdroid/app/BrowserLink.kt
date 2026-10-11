package com.alpdroid.app

import android.content.Context
import android.net.Uri

/**
 * Reads AlpBrowser's control-API connection details directly from the companion app (token + the actually-bound
 * control/proxy ports), so the agent never has to copy the token by hand. Goes through AlpBrowser's
 * signature-protected ContentProvider — only works because both apps are signed with the same key — and is used to
 * provision `browserctl` and an env file into the Alpine rootfs on session start (see [AlpineSession]).
 */
object BrowserLink {
    data class Info(val token: String, val controlPort: Int, val proxyPort: Int)

    private val AUTHORITY_URI: Uri = Uri.parse("content://com.alpdroid.browser.control")

    /** Null when AlpBrowser is not installed, its provider is unreachable, or it hasn't started yet. */
    fun query(context: Context): Info? = runCatching {
        val b = context.contentResolver.call(AUTHORITY_URI, "info", null, null) ?: return null
        val token = b.getString("token")?.takeIf { it.isNotBlank() } ?: return null
        Info(token, b.getInt("controlPort", 8899), b.getInt("proxyPort", 8890))
    }.getOrNull()
}
