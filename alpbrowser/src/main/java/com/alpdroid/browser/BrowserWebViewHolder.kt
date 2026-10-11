package com.alpdroid.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One browser tab. Its WebView is created lazily, so a restored or background tab costs no memory until it is opened. */
class BrowserTab(val id: Int, initialUrl: String) {
    var webView by mutableStateOf<WebView?>(null)
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    // Load state for the control API (read from other threads, so plain volatiles rather than Compose state).
    @Volatile var loading: Boolean = false
    /** Counts navigations started in this tab, so a caller can tell "the one I just asked for" from an earlier one. */
    @Volatile var navSeq: Int = 0
    @Volatile var httpStatus: Int = 0
    @Volatile var lastError: String? = null
    /** True while our own error page is being loaded in place of a failed page (so that load is not counted as a new navigation). */
    @Volatile var errorPage: Boolean = false
    @Volatile var lastActiveAt: Long = System.currentTimeMillis()
}

data class TabInfo(val id: Int, val title: String, val url: String, val active: Boolean, val loading: Boolean = false, val lastActiveAt: Long = 0L)

/**
 * Owns the browser's tabs and their WebViews for the whole app process, instead of one per visit to the Browser
 * screen: switching to another screen and back reattaches the same, already-loaded pages. The open tabs (their URLs)
 * are remembered across restarts; a restored tab loads when it is first selected.
 */
class BrowserWebViewHolder(private val appContext: Context) {
    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableIntStateOf(-1)
        private set
    private var nextId = 1
    private val prefs = appContext.getSharedPreferences("ronin_browser_state", Context.MODE_PRIVATE)

    init { restore() }

    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }

    /** The WebView of the tab in front, or null before it has been created. */
    val current: WebView? get() = active?.webView

    val allWebViews: List<WebView> get() = tabs.mapNotNull { it.webView }

    fun info(): List<TabInfo> = tabs.map { TabInfo(it.id, it.title.ifEmpty { it.url }, it.url, it.id == activeId, it.loading, it.lastActiveAt) }

    /** Adds a tab (and brings it to the front unless [activate] is false). Null when [MAX_TABS] is reached. */
    fun open(url: String, activate: Boolean = true): BrowserTab? {
        if (tabs.size >= MAX_TABS) return null
        val tab = BrowserTab(nextId++, url)
        tabs.add(tab)
        if (activate) activeId = tab.id
        persist()
        return tab
    }

    fun switchTo(id: Int): Boolean {
        val t = tabs.firstOrNull { it.id == id } ?: return false
        t.lastActiveAt = System.currentTimeMillis()
        activeId = id
        persist()
        return true
    }

    /** Closes a tab and frees its WebView. Closing the last tab leaves a fresh start page instead of nothing. */
    fun close(id: Int): Boolean {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return false
        val tab = tabs.removeAt(i)
        tab.webView?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            runCatching { wv.stopLoading(); wv.destroy() }
        }
        tab.webView = null
        if (tabs.isEmpty()) open(DEFAULT_URL) else if (activeId == id) activeId = tabs[minOf(i, tabs.size - 1)].id
        persist()
        return true
    }

    /** Creates the tab's WebView on first use. Main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(tab: BrowserTab, configure: (WebView) -> Unit): WebView {
        tab.webView?.let { return it }
        val created = WebView(appContext)
        configure(created)
        tab.webView = created
        return created
    }

    fun persist() {
        runCatching {
            val urls = JSONArray()
            tabs.forEach { urls.put(it.url.ifEmpty { DEFAULT_URL }) }
            prefs.edit().putString(KEY_TABS, JSONObject().put("urls", urls).put("active", tabs.indexOfFirst { it.id == activeId }).toString()).apply()
        }
    }

    private fun restore() {
        val saved = runCatching { JSONObject(prefs.getString(KEY_TABS, "") ?: "") }.getOrNull()
        val urls = saved?.optJSONArray("urls")
        if (urls != null && urls.length() > 0) {
            for (i in 0 until minOf(urls.length(), MAX_TABS)) tabs.add(BrowserTab(nextId++, urls.optString(i).ifEmpty { DEFAULT_URL }))
            activeId = tabs[(saved.optInt("active", 0)).coerceIn(0, tabs.size - 1)].id
        } else {
            // First run, or an upgrade from the single-page version: continue from the last page that was open.
            val tab = BrowserTab(nextId++, prefs.getString(KEY_LAST_URL, DEFAULT_URL) ?: DEFAULT_URL)
            tabs.add(tab); activeId = tab.id
        }
    }

    companion object {
        const val MAX_TABS = 12
        const val DEFAULT_URL = "https://www.google.com"
        private const val KEY_TABS = "tabs"
        private const val KEY_LAST_URL = "last_url"
    }
}
