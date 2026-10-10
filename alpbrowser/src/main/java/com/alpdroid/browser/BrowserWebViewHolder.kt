package com.alpdroid.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView

/**
 * Keeps a single WebView instance alive for the whole app process instead
 * of one per visit to the Browser tab. Without this, NavHost disposes the
 * Browser composable on every tab switch, so `AndroidView`'s factory ran
 * again from scratch on every return trip — a full WebView + JS engine
 * boot plus a fresh page load, which is most of why navigation felt heavy.
 * Holding it here means switching back to Browser just reattaches the
 * same, already-loaded WebView.
 */
class BrowserWebViewHolder(private val appContext: Context) {
    private var webView: WebView? = null
    private val prefs = appContext.getSharedPreferences("ronin_browser_state", Context.MODE_PRIVATE)

    /**
     * Persisted (not a plain var): without this, a cold app restart always reopened the Browser
     * tab on the hardcoded default instead of wherever the user actually was — the one piece of
     * Browser state that isn't already covered by the WebView's own on-disk cache/cookies.
     */
    var lastUrl: String
        get() = prefs.getString(KEY_LAST_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_LAST_URL, value).apply()

    /** Null until the Browser tab has been opened at least once this process. */
    val current: WebView? get() = webView

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(configure: (WebView) -> Unit): WebView {
        webView?.let { return it }
        val created = WebView(appContext)
        configure(created)
        webView = created
        return created
    }

    private companion object {
        const val KEY_LAST_URL = "last_url"
        const val DEFAULT_URL = "https://www.google.com"
    }
}
