package com.alpdroid.browser

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Separate cookie and storage jars for tabs ("profiles"): `tab new --profile alice` opens a tab that has its own logins,
 * next to another tab with a different one. Needs a WebView that supports profiles (Chrome 128+). Every function here
 * must run on the main thread. A tab asking for a profile that cannot be provided is never silently given the shared one.
 */
object Profiles {
    private val validName = Regex("^[A-Za-z0-9_-]{1,32}$")

    fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    fun validName(name: String): Boolean = validName.matches(name) && !name.equals("Default", ignoreCase = true)

    /** Puts [wv] in [name]'s profile (created if new). Must happen before the WebView loads anything; throws when it cannot. */
    fun assign(wv: WebView, name: String) {
        check(supported()) { "this WebView does not support separate profiles (it needs Chrome 128 or newer)" }
        require(validName(name)) { "a profile name is 1-32 letters, digits, - or _" }
        ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(wv, name)
        check(WebViewCompat.getProfile(wv).name == name) { "the WebView did not accept the profile" }
    }

    /** The cookie jar of the named profile, or null when it does not exist. */
    fun cookieManager(name: String): CookieManager? = runCatching { ProfileStore.getInstance().getProfile(name)?.cookieManager }.getOrNull()

    fun names(): List<String> = if (!supported()) emptyList() else runCatching { ProfileStore.getInstance().allProfileNames.filter { !it.equals("Default", ignoreCase = true) } }.getOrDefault(emptyList())

    /** Deletes a profile and its data; fails while a tab still uses it. */
    fun delete(name: String): Boolean = supported() && validName(name) && runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)

    /** Clears cookies and web storage in every named profile (used by "clear"). */
    fun clearAll(cookies: Boolean, storage: Boolean) {
        for (n in names()) runCatching {
            val p = ProfileStore.getInstance().getProfile(n) ?: return@runCatching
            if (cookies) { p.cookieManager.removeAllCookies(null); p.cookieManager.flush() }
            if (storage) p.webStorage.deleteAllData()
        }
    }
}
