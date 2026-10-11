package com.alpdroid.browser.ui.browser

import android.annotation.SuppressLint
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.alpdroid.browser.AlpBrowserApp
import com.alpdroid.browser.ui.theme.RoninMono
import com.alpdroid.proxy.BrowserCommand
import java.io.ByteArrayInputStream

private const val HOME_URL = "https://www.google.com"
private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

/**
 * The browser surface, sized for a phone: a single compact toolbar (back / forward / address /
 * menu), a thin load bar, and the page. Traffic is pointed at the in-process MITM proxy via
 * ProxyController; the single WebView is owned by [com.alpdroid.browser.BrowserWebViewHolder] on the
 * Application so it survives recomposition. A strong ad/tracker blocker runs in
 * shouldInterceptRequest; the in-app settings sheet (the ⋮ menu) toggles it, JavaScript, desktop
 * mode, remote DevTools and the bug-bounty/web-dev mode, and clears browsing data.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(proxyPort: Int, onOpenControlInfo: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AlpBrowserApp

    var urlField by remember { mutableStateOf(app.browserWebViewHolder.lastUrl) }
    var loadProgress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var webView by remember { mutableStateOf(app.browserWebViewHolder.current) }
    LaunchedEffect(Unit) {
        if (webView == null) {
            installProxyOverride(proxyPort)
            // DevTools/CDP is a process-wide debugging socket any local app could attach to, so it
            // is opt-in (default off). The agent/user turns it on from the settings sheet.
            WebView.setWebContentsDebuggingEnabled(app.devtoolsEnabled())
            webView = app.browserWebViewHolder.getOrCreate { wv ->
                // The default WebView surface is opaque white until the page's own CSS background
                // paints — on a dark-themed app that reads as a jarring white flash on every
                // navigation, not just first load. Matching our background removes that flash; the
                // page's real background still shows through the instant it has one.
                wv.setBackgroundColor(0xFF0B0C10.toInt())
                wv.settings.javaScriptEnabled = app.jsEnabled()
                wv.settings.domStorageEnabled = true
                wv.settings.safeBrowsingEnabled = false // never block a pentest target
                wv.settings.useWideViewPort = true
                wv.settings.loadWithOverviewMode = true
                // Pinch-to-zoom: on by default in a real browser. builtInZoomControls draws Android's
                // own +/- controls too, which displayZoomControls=false hides (pinch alone drives it).
                wv.settings.setSupportZoom(true)
                wv.settings.builtInZoomControls = true
                wv.settings.displayZoomControls = false
                if (app.desktopMode()) wv.settings.userAgentString = DESKTOP_UA else wv.settings.userAgentString = defaultUserAgent(context)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        urlField = url
                        app.browserWebViewHolder.lastUrl = url
                        canGoBack = view.canGoBack()
                        canGoForward = view.canGoForward()
                    }

                    // Strong default ad/tracker blocker: a blocked host gets an empty 200 instead of
                    // reaching the network. Runs on the WebView's IO thread (AdBlocker is thread-safe).
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (app.adBlocker.shouldBlock(request.url?.host)) {
                            app.adBlocker.blockedCount.incrementAndGet()
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }

                    // Every byte routes through our proxy, so the only untrusted cert the WebView can
                    // see should be a leaf our own CA signed. Proceed on UNTRUSTED *only after*
                    // confirming the leaf actually chains to our CA; anything else cancels.
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        if (error.primaryError == SslError.SSL_UNTRUSTED && isOurLeaf(error.certificate, app)) handler.proceed()
                        else handler.cancel()
                    }
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) { loadProgress = newProgress }
                    // Web-development workflow: mirror the page's console into a buffer the agent can read.
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        app.console.add(m.messageLevel().name, m.message(), m.sourceId() ?: "", m.lineNumber())
                        return true
                    }
                }
                wv.loadUrl(normalizeUrl(app.browserWebViewHolder.lastUrl))
            }
            // Keep the process (proxy, control bridge, this WebView's JS timers) alive once there is
            // something to keep alive — so switching to AlpDroid to drive the agent doesn't freeze
            // the page or stall network capture. A plain background Activity gets throttled/killed by
            // Android without this.
            startKeepAlive(context)
        }
    }
    val currentWebView = webView
    if (currentWebView == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text("Starting Browser…", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
        return
    }
    canGoBack = currentWebView.canGoBack()
    canGoForward = currentWebView.canGoForward()

    LaunchedEffect(app.browserCommandBus) {
        app.browserCommandBus.commands.collect { command ->
            when (command) {
                is BrowserCommand.Navigate -> currentWebView.loadUrl(normalizeUrl(command.url))
            }
        }
    }

    val secure = urlField.startsWith("https://")

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                NavIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", enabled = canGoBack) { currentWebView.goBack() }
                NavIcon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", enabled = canGoForward) { currentWebView.goForward() }
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    modifier = Modifier.weight(1f).height(50.dp).padding(horizontal = 4.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono),
                    shape = RoundedCornerShape(25.dp),
                    leadingIcon = {
                        Icon(
                            if (secure) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (secure) "Secure" else "Not secure",
                            tint = if (secure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { currentWebView.reload() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, "Reload", modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { currentWebView.loadUrl(normalizeUrl(urlField)) }),
                )
                NavIcon(Icons.Default.MoreVert, "Menu") { showSettings = true }
            }
        }

        if (loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = {
                (currentWebView.parent as? ViewGroup)?.removeView(currentWebView)
                currentWebView
            },
        )
    }

    if (showSettings) {
        BrowserSettingsSheet(
            app = app,
            onHome = { currentWebView.loadUrl(HOME_URL) },
            onReload = { currentWebView.reload() },
            onApplyJs = { currentWebView.settings.javaScriptEnabled = it; currentWebView.reload() },
            onApplyDesktop = {
                // Explicit mobile UA on the way back (not null): a null userAgentString doesn't
                // reliably restore WebView's real mobile default on every OEM build. Also reset the
                // text zoom some sites' desktop CSS leaves oversized, and re-request layout so the
                // wide viewport actually re-measures instead of only reloading with the old metrics.
                currentWebView.settings.userAgentString = if (it) DESKTOP_UA else defaultUserAgent(context)
                currentWebView.settings.textZoom = 100
                currentWebView.reload()
            },
            onOpenControlInfo = onOpenControlInfo,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun NavIcon(icon: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
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

private fun installProxyOverride(proxyPort: Int) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
    val config = ProxyConfig.Builder()
        .addProxyRule("127.0.0.1:$proxyPort")
        .removeImplicitRules()
        .build()
    ProxyController.getInstance().setProxyOverride(config, { command -> command.run() }, {})
}

private fun normalizeUrl(input: String): String =
    if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"

/** The real mobile UA this WebView build would use — queried fresh rather than cached, so turning
 *  desktop mode back off restores exactly what the device actually ships, not a guessed string. */
private fun defaultUserAgent(context: android.content.Context): String =
    runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }.getOrDefault("")

/** Starts [com.alpdroid.browser.BrowserKeepAliveService] so the process stays at foreground
 *  priority once there's a live page — safe to call repeatedly (a running foreground service is a
 *  no-op restart). */
private fun startKeepAlive(context: android.content.Context) {
    runCatching {
        val intent = android.content.Intent(context, com.alpdroid.browser.BrowserKeepAliveService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}
