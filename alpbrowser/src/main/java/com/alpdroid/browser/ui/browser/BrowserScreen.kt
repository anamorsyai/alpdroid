package com.alpdroid.browser.ui.browser

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

private const val HOME_URL = "https://www.google.com"

/**
 * The browser surface. Its traffic is pointed at the in-process MITM proxy via ProxyController; the single WebView is
 * owned by [com.alpdroid.browser.BrowserWebViewHolder] on the Application so it survives recomposition. Remote
 * debugging is enabled so the AlpDroid agent can also attach a CDP/DevTools client to the live page.
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

    var webView by remember { mutableStateOf(app.browserWebViewHolder.current) }
    LaunchedEffect(Unit) {
        if (webView == null) {
            installProxyOverride(proxyPort)
            // DevTools/CDP: lets the agent drive the live page over chrome://inspect-style remote debugging.
            WebView.setWebContentsDebuggingEnabled(true)
            webView = app.browserWebViewHolder.getOrCreate { wv ->
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.safeBrowsingEnabled = false // never block a pentest target
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        urlField = url
                        app.browserWebViewHolder.lastUrl = url
                        canGoBack = view.canGoBack()
                        canGoForward = view.canGoForward()
                    }

                    // Every byte routes through our proxy, so the only untrusted cert the WebView can see is our own
                    // leaf. Proceed on UNTRUSTED only; any other SSL error still cancels.
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        if (error.primaryError == SslError.SSL_UNTRUSTED) handler.proceed() else handler.cancel()
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CompactNavIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", enabled = canGoBack) { currentWebView.goBack() }
            CompactNavIcon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", enabled = canGoForward) { currentWebView.goForward() }
            CompactNavIcon(Icons.Default.Home, "Home") { currentWebView.loadUrl(HOME_URL) }
            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = RoninMono),
                shape = RoundedCornerShape(19.dp),
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp))
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { currentWebView.loadUrl(normalizeUrl(urlField)) }),
            )
            CompactNavIcon(Icons.Default.Refresh, "Reload") { currentWebView.reload() }
            CompactNavIcon(Icons.Default.Settings, "Agent control", onClick = onOpenControlInfo)
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("⏎ Enter navigates · ⚙ shows the agent-control token", style = MaterialTheme.typography.labelSmall.copy(fontFamily = RoninMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (loadProgress in 1..99) {
            LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.outline)
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = {
                (currentWebView.parent as? ViewGroup)?.removeView(currentWebView)
                currentWebView
            },
        )
    }
}

@Composable
private fun CompactNavIcon(icon: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
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
