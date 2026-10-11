package com.alpdroid.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Puts the enabled userscripts into the live WebView. With document-start script support (modern WebView) each one is
 * registered to run before the page's own scripts, in every frame; without it they are evaluated when a page finishes
 * loading instead (so `document-start` scripts then run a little late). Call [refresh] after any install / toggle /
 * remove and [onPageFinished] from the WebViewClient.
 */
class ExtensionInjector(private val store: ExtensionStore, private val webViews: () -> List<WebView>) {
    private val main = Handler(Looper.getMainLooper())
    private val handles = java.util.WeakHashMap<WebView, MutableList<ScriptHandler>>()
    private val supportsStart: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** Re-registers every enabled script on every open tab. Safe from any thread. */
    fun refresh() {
        main.post { webViews().forEach(::registerNow) }
    }

    /** Main thread only. Used directly before the first page load so even that page sees the scripts. */
    fun registerNow(wv: WebView) {
        handles.remove(wv)?.forEach { runCatching { it.remove() } }
        if (!supportsStart) return
        val mine = ArrayList<ScriptHandler>()
        for (s in store.list().filter { it.enabled }) {
            runCatching { mine += WebViewCompat.addDocumentStartJavaScript(wv, UserScriptRunner.wrap(s), setOf("*")) }
        }
        handles[wv] = mine
    }

    /** Fallback path for WebViews without document-start scripts. */
    fun onPageFinished(view: WebView) {
        if (supportsStart) return
        for (s in store.list().filter { it.enabled }) view.evaluateJavascript(UserScriptRunner.wrap(s), null)
    }
}
