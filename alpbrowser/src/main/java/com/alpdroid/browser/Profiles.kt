package com.alpdroid.browser

import android.content.SharedPreferences
import android.util.Log
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
        revive(name)
        ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(wv, name)
        check(WebViewCompat.getProfile(wv).name == name) { "the WebView did not accept the profile" }
    }

    /** The cookie jar of the named profile, or null when it does not exist. */
    fun cookieManager(name: String): CookieManager? = runCatching { ProfileStore.getInstance().getProfile(name)?.cookieManager }.getOrNull()

    private const val PENDING = "profiles_pending_delete"
    private var prefs: SharedPreferences? = null

    /** Profiles whose deletion the engine refused earlier; they are emptied, hidden, and removed for real at the next start. */
    private fun pending(): MutableSet<String> = prefs?.getStringSet(PENDING, emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun savePending(s: Set<String>) { prefs?.edit()?.putStringSet(PENDING, s)?.apply() }

    /** Call once at start-up (main thread): finishes the deletions that could not be done while a WebView still held the profile. */
    fun init(p: SharedPreferences) {
        prefs = p
        if (!supported()) return
        val left = pending()
        for (n in left.toList()) {
            val gone = runCatching { ProfileStore.getInstance().getProfile(n) == null || ProfileStore.getInstance().deleteProfile(n) }.getOrDefault(false)
            if (gone) left.remove(n)
        }
        savePending(left)
    }

    fun names(): List<String> = if (!supported()) emptyList() else {
        val hidden = pending()
        runCatching { ProfileStore.getInstance().allProfileNames.filter { !it.equals("Default", ignoreCase = true) && it !in hidden } }.getOrDefault(emptyList())
    }

    /**
     * Deletes a profile and its data. The engine releases a closed tab's profile late (on some versions only after a restart),
     * so when it still refuses, the profile is emptied and hidden at once and removed for real at the next start; the name
     * can be reused right away. Callers must not call this for a profile an open tab uses.
     */
    fun delete(name: String): Boolean {
        if (!supported() || !validName(name)) return false
        val store = ProfileStore.getInstance()
        if (name !in names()) return false
        val done = try { store.deleteProfile(name) } catch (e: Throwable) { Log.w("Profiles", "deleteProfile($name) refused: $e"); false }
        if (done) { savePending(pending().apply { remove(name) }); return true }
        return runCatching {
            val p = store.getProfile(name) ?: return@runCatching false
            p.cookieManager.removeAllCookies(null); p.cookieManager.flush()
            p.webStorage.deleteAllData()
            savePending(pending().apply { add(name) })
            true
        }.getOrDefault(false)
    }

    /** A profile asked for again after a deferred delete starts empty and visible again. */
    private fun revive(name: String) { val p = pending(); if (p.remove(name)) savePending(p) }

    /** Clears cookies and web storage in every named profile (used by "clear"). */
    fun clearAll(cookies: Boolean, storage: Boolean) {
        for (n in names()) runCatching {
            val p = ProfileStore.getInstance().getProfile(n) ?: return@runCatching
            if (cookies) { p.cookieManager.removeAllCookies(null); p.cookieManager.flush() }
            if (storage) p.webStorage.deleteAllData()
        }
    }
}
