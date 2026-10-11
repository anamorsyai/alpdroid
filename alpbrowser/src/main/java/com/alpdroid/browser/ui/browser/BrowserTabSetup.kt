package com.alpdroid.browser.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.BrowserTab
import java.io.ByteArrayInputStream

internal const val HOME_URL = "https://www.google.com"
internal const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

/**
 * Creates [tab]'s WebView (if it has none yet) and wires it up: settings, the ad blocker, our proxy-CA SSL check, the
 * console mirror, extensions, and tab state (title, progress, back/forward). Main thread only.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun ensureTabWebView(context: Context, app: AlpBrowserApp, tab: BrowserTab): WebView {
    val holder = app.browserWebViewHolder
    return holder.ensureWebView(tab) { wv ->
        // The default WebView surface is opaque white until the page's own CSS paints, which reads as a jarring flash
        // on a dark app; matching our background removes that flash.
        wv.setBackgroundColor(0xFF0B0C10.toInt())
        wv.settings.javaScriptEnabled = app.jsEnabled()
        wv.settings.domStorageEnabled = true
        wv.settings.safeBrowsingEnabled = false // never block a pentest target
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.setSupportMultipleWindows(true) // links that open a new window become a new tab
        wv.settings.userAgentString = if (app.desktopMode()) DESKTOP_UA else defaultUserAgent(context)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                tab.url = url
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.url = url
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
                holder.persist()
                app.extensionInjector.onPageFinished(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
            }

            // Strong default ad/tracker blocker: a blocked host gets an empty 200 instead of reaching the network.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (app.adBlocker.shouldBlock(request.url?.host)) {
                    app.adBlocker.blockedCount.incrementAndGet()
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (!app.proxyActive()) app.recordDirect(request.url, request.method ?: "GET", request.requestHeaders ?: emptyMap())
                return null
            }

            // Every byte routes through our proxy while it is on, so the only untrusted cert the WebView can see should
            // be a leaf our own CA signed. Proceed on UNTRUSTED only after confirming the leaf chains to our CA.
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (error.primaryError == SslError.SSL_UNTRUSTED && isOurLeaf(error.certificate, app)) handler.proceed()
                else handler.cancel()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title.orEmpty() }

            // Web-development workflow: mirror the page's console into a buffer the agent can read.
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                app.console.add(m.messageLevel().name, m.message(), m.sourceId() ?: "", m.lineNumber())
                return true
            }

            // window.open / target=_blank: only on a real tap (no pop-up spam), and into a new tab.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val child = holder.open("", activate = true) ?: return false
                val childView = ensureTabWebView(context, app, child)
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = childView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                holder.tabs.firstOrNull { it.webView === window }?.let { holder.close(it.id) }
            }
        }
        app.applyProxyMode()
        app.extensionInjector.registerNow(wv)
        if (tab.url.isNotEmpty()) wv.loadUrl(normalizeUrl(tab.url))
    }
}

/** Opens [url] in a new tab (front by default), creating its WebView right away. Null when the tab limit is reached. */
internal fun openTab(context: Context, app: AlpBrowserApp, url: String, activate: Boolean = true): BrowserTab? {
    val tab = app.browserWebViewHolder.open(url, activate) ?: return null
    ensureTabWebView(context, app, tab)
    return tab
}

/** True when [cert] is a leaf our own MITM CA signed (API 29+: verify the signature; older: issuer CN match). */
private fun isOurLeaf(cert: SslCertificate?, app: AlpBrowserApp): Boolean {
    if (cert == null) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val x = cert.x509Certificate
        val pub = app.caPublicKey
        if (x != null && pub != null) return runCatching { x.verify(pub); true }.getOrDefault(false)
    }
    val issuer = cert.issuedBy
    return issuer.cName == AlpBrowserApp.CA_CN || issuer.dName.contains(AlpBrowserApp.CA_CN)
}

internal fun normalizeUrl(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file://") -> t
        // Something that doesn't look like an address (spaces, no dot) is a search, as in any browser.
        t.contains(' ') || (!t.contains('.') && !t.contains(':') && !t.equals("localhost", true)) ->
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        else -> "https://$t"
    }
}

/** The real mobile UA this WebView build would use — queried fresh rather than cached. */
internal fun defaultUserAgent(context: Context): String =
    runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
