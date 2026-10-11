package com.alpdroid.browser

import android.app.Application
import android.os.Handler
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import android.os.Looper
import android.util.Log
import com.alpdroid.proxy.BrowserCommandBus
import com.alpdroid.proxy.ProxyEngine
import com.alpdroid.proxy.ScopeFilter
import com.alpdroid.proxy.TlsCa
import com.alpdroid.proxy.TlsMitm
import com.alpdroid.proxy.TrafficSource
import com.alpdroid.proxy.TransactionStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The whole AlpBrowser process wired together: the MITM [ProxyEngine] the WebView points at, the [InterceptController]
 * that lets the agent pause/modify/drop traffic, and the [BrowserBridge] control API the AlpDroid agent drives. One
 * process-wide WebView ([browserWebViewHolder]). No terminal/proot — this app is only the browser + its control plane.
 */
class AlpBrowserApp : Application() {
    val transactionStore = TransactionStore()
    val scopeFilter = ScopeFilter()
    val browserCommandBus = BrowserCommandBus()
    val intercept = InterceptController()
    val console = ConsoleBuffer()
    val repeater = com.alpdroid.browser.ui.proxy.RepeaterModel()
    val adBlocker by lazy { AdBlocker(this) }
    val browserWebViewHolder by lazy { BrowserWebViewHolder(this) }

    /** Installed extensions (userscripts), kept in private storage so they stay installed until removed. */
    /** Page automation for the control API (element commands, waits, screenshots, cookies). */
    val pageAutomation by lazy { PageAutomation(this) }
    /** When the WebView last asked for a resource; used to decide "network idle". */
    @Volatile var lastRequestAt: Long = 0L

    val extensions by lazy { ExtensionStore(java.io.File(filesDir, "extensions")) }
    val extensionInjector by lazy { ExtensionInjector(extensions) { browserWebViewHolder.allWebViews } }

    private val prefs by lazy { getSharedPreferences("alpbrowser", MODE_PRIVATE) }

    /** User-facing browser settings, persisted. Defaults chosen for a safe, lean mobile browser. */
    fun adblockEnabled(): Boolean = prefs.getBoolean("adblock", true)
    fun setAdblockEnabled(on: Boolean) { prefs.edit().putBoolean("adblock", on).apply(); adBlocker.enabled = on }

    fun jsEnabled(): Boolean = prefs.getBoolean("js", true)
    fun setJsEnabled(on: Boolean) = prefs.edit().putBoolean("js", on).apply()

    fun desktopMode(): Boolean = prefs.getBoolean("desktop", false)
    fun setDesktopMode(on: Boolean) = prefs.edit().putBoolean("desktop", on).apply()

    /** Chrome remote debugging (CDP/DevTools). OFF by default: it opens a process-wide debugging
     *  socket any local app could attach to, so it is an explicit opt-in the agent/user turns on. */
    /** Open local access: programs on this phone (AlpDroid, browserctl, Playwright) need no token. On by default. */
    fun openLocalAccess(): Boolean = prefs.getBoolean("openLocal", true)
    fun setOpenLocalAccess(on: Boolean) { prefs.edit().putBoolean("openLocal", on).apply() }

    fun devtoolsEnabled(): Boolean = prefs.getBoolean("devtools", false)
    fun setDevtoolsEnabled(on: Boolean) {
        prefs.edit().putBoolean("devtools", on).apply()
        main.post { runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(on) } }
        if (on) cdpForwarder.start(CDP_PORT) else cdpForwarder.stop()
    }

    /**
     * WebView's DevTools lives on an abstract unix socket only this app can open; this relays it to a loopback TCP
     * port (token-gated) so a CDP client inside the proot shell can attach. See [CdpForwarder].
     */
    val cdpForwarder by lazy {
        CdpForwarder(controlToken, {
            var last: Exception? = null
            for (name in listOf("webview_devtools_remote_${android.os.Process.myPid()}", "chrome_devtools_remote")) {
                try {
                    val sock = android.net.LocalSocket()
                    sock.connect(android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT))
                    return@CdpForwarder CdpForwarder.Link(sock.inputStream, sock.outputStream, { runCatching { sock.close() } })
                } catch (e: Exception) { last = e }
            }
            throw java.io.IOException("DevTools socket not found", last)
        }, { openLocalAccess() })
    }

    private val cdpControl = object : CdpControl {
        override fun status(): org.json.JSONObject {
            val on = devtoolsEnabled()
            val out = org.json.JSONObject().put("ok", true).put("enabled", on).put("port", if (on) cdpForwarder.port else 0)
            if (!on || cdpForwarder.port == 0) return out
            val base = "http://127.0.0.1:${cdpForwarder.port}" + if (openLocalAccess()) "" else "/$controlToken"
            out.put("browserUrl", base)
            // Ask DevTools (through our own forwarder) for the browser-level WebSocket URL, with the token already in it.
            runCatching {
                val conn = (java.net.URL("$base/json/version").openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = 5000 }
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val j = org.json.JSONObject(text)
                out.put("webSocketDebuggerUrl", j.optString("webSocketDebuggerUrl")).put("browser", j.optString("Browser"))
            }.onFailure { out.put("note", "DevTools is enabled but did not answer yet: open a page in AlpBrowser and retry (${it.message})") }
            return out
        }

        override fun set(enabled: Boolean, preferredPort: Int?): org.json.JSONObject {
            if (enabled && preferredPort != null && cdpForwarder.port == 0) {
                prefs.edit().putBoolean("devtools", true).apply()
                main.post { runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(true) } }
                cdpForwarder.start(preferredPort)
            } else setDevtoolsEnabled(enabled)
            return status()
        }
    }



    /**
     * Capture: route the page through the intercepting proxy so History holds full, decrypted requests and responses.
     * OFF by default — with it off the WebView talks to the network directly (full Chromium speed, HTTP/2, QUIC) and
     * History only lists the URLs it loaded. Intercept being on also needs the proxy, whatever this says.
     */
    fun captureEnabled(): Boolean = prefs.getBoolean("capture", false)
    fun setCaptureEnabled(on: Boolean, reload: Boolean = true) {
        prefs.edit().putBoolean("capture", on).apply()
        applyProxyMode(reload)
    }
    fun proxyActive(): Boolean = captureEnabled() || intercept.enabled

    private var proxyApplied: Boolean? = null

    /** Points the WebView at the proxy or straight at the network, to match [proxyActive]. Safe from any thread. */
    fun applyProxyMode(reload: Boolean = false) {
        val block = Runnable {
            val want = proxyActive()
            if (proxyApplied != want && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                proxyApplied = want
                if (want) {
                    engineScope.launch { runCatching { tlsMitm?.prewarm() } }
                    val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:${browserEngine.boundPort}").removeImplicitRules().build()
                    ProxyController.getInstance().setProxyOverride(config, { it.run() }, {})
                } else {
                    ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
                }
                if (reload) browserWebViewHolder.allWebViews.forEach { it.reload() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block.run() else main.post(block)
    }

    /** Direct mode: no proxy sees the traffic, so note the request itself (URL, method, headers) in History. */
    fun recordDirect(url: android.net.Uri, method: String, headers: Map<String, String>) {
        val scheme = url.scheme ?: return
        if (scheme != "http" && scheme != "https") return
        val host = url.host ?: return
        if (!scopeFilter.matches(host)) return
        val port = if (url.port != -1) url.port else if (scheme == "https") 443 else 80
        val path = (url.encodedPath ?: "/").ifEmpty { "/" } + (url.encodedQuery?.let { "?$it" } ?: "")
        transactionStore.record(
            com.alpdroid.proxy.HttpTransaction(
                id = java.util.UUID.randomUUID().toString(), source = TrafficSource.BROWSER, timestamp = java.time.Instant.now(),
                method = method, scheme = scheme, host = host, port = port, path = path,
                requestHeaders = headers.map { it.key to it.value },
            ),
        )
    }

    /** "bugbounty" (intercept + rules + scope, default) or "webdev" (console + eval + cache control). */
    fun mode(): String = prefs.getString("mode", "bugbounty") ?: "bugbounty"
    fun setMode(m: String) {
        prefs.edit().putString("mode", m).apply()
        if (m == "webdev") { intercept.enabled = false; setCaptureEnabled(false) } // web dev watches, it does not pause traffic
        else setCaptureEnabled(true) // bug bounty needs the decrypted traffic
    }

    // Loaded on first use, not in the constructor: SharedPreferences is unavailable before the Application has a
    // base context, which used to make the saved scope silently come back empty on every start.
    @Volatile private var loadedScope: List<String>? = null
    private var scopePatterns: List<String>
        get() = loadedScope ?: runCatching { prefs.getString("scope", "")!!.split("\n").filter { it.isNotBlank() } }
            .getOrDefault(emptyList()).also { loadedScope = it }
        set(value) { loadedScope = value }
    fun scope(): List<String> = scopePatterns
    fun setScope(list: List<String>) {
        scopePatterns = list
        scopeFilter.setPatterns(list)
        prefs.edit().putString("scope", list.joinToString("\n")).apply()
    }

    /** Token for the control API, generated once per install and shown in the UI so the agent can be pointed at it. */
    val controlToken: String by lazy {
        val prefs = getSharedPreferences("alpbrowser", MODE_PRIVATE)
        prefs.getString("control_token", null) ?: run {
            val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = java.security.SecureRandom()
            val t = (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
            prefs.edit().putString("control_token", t).commit()
            t
        }
    }

    private val caStore by lazy { TlsCaStore(this) }
    private val tlsMitm: TlsMitm? by lazy { runCatching { TlsMitm(caStore.loadOrGenerate()) }.getOrNull() }

    /** Public key of our MITM root CA — the WebView's SSL-error handler verifies that the only
     *  "untrusted" leaf it proceeds on is actually one our own proxy signed (see BrowserScreen). */
    val caPublicKey: java.security.PublicKey? by lazy { runCatching { caStore.loadOrGenerate().certificate.publicKey }.getOrNull() }

    val browserEngine by lazy {
        ProxyEngine(
            port = PROXY_PORT,
            source = TrafficSource.BROWSER,
            store = transactionStore,
            scope = scopeFilter,
            maxCapturedBodyBytes = 512 * 1024, // enough to read a typical API response or page in full (the store has a memory budget)
            respectScope = true, // empty scope = capture everything; set a scope to narrow it (bug-bounty)
            tlsMitmProvider = { tlsMitm },
            interceptor = intercept.interceptor,
        )
    }

    private val tabsHost = object : TabsHost {
        private val holder get() = browserWebViewHolder
        override fun list() = holder.info()
        override fun open(url: String, wait: Boolean, timeoutMs: Long): Int? {
            val tab = onMainBlocking {
                com.alpdroid.browser.ui.browser.openTab(
                    this@AlpBrowserApp, this@AlpBrowserApp,
                    com.alpdroid.browser.ui.browser.normalizeUrl(url.ifBlank { BrowserWebViewHolder.DEFAULT_URL }),
                )
            } ?: return null
            // Returning while the tab is still blank reads as success; wait for the page unless the caller said not to.
            if (wait && url.isNotBlank()) tab.webView?.let { pageAutomation.awaitLoad(tab, it, timeoutMs) }
            return tab.id
        }
        override fun switchTo(id: Int) = onMainBlocking { holder.switchTo(id) } ?: false
        override fun close(id: Int) = onMainBlocking { holder.close(id) } ?: false
    }

    /** Runs [block] on the main thread and waits (briefly) for its result; WebViews may only be touched there. */
    private fun <T> onMainBlocking(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out: T? = null
        val latch = CountDownLatch(1)
        main.post { try { out = block() } finally { latch.countDown() } }
        return if (latch.await(5, TimeUnit.SECONDS)) out else null
    }

    val bridge by lazy {
        BrowserBridge(
            token = controlToken,
            store = transactionStore,
            intercept = intercept,
            proxyPort = { browserEngine.boundPort },
            caPem = { runCatching { TlsCa.encodeCertificate(caStore.loadOrGenerate().certificate) }.getOrNull() },
            onNavigate = { url -> engineScope.launch { browserCommandBus.navigate(url) } },
            onEvalJs = ::evaluateBrowserJsBlocking,
            onResend = ::httpResend,
            scopeGet = ::scope,
            scopeSet = ::setScope,
            console = console,
            modeGet = ::mode,
            modeSet = ::setMode,
            captureGet = ::captureEnabled,
            captureSet = { setCaptureEnabled(it) },
            tabs = tabsHost,
            page = pageAutomation,
            appVersion = BuildConfig.VERSION_NAME,
            cdp = cdpControl,
            openAccess = ::openLocalAccess,
            onClear = ::clearBrowserData,
            preferredPort = CONTROL_PORT,
        )
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private val resendPool = Executors.newCachedThreadPool { r -> Thread(r, "alpbrowser-resend").apply { isDaemon = true } }

    override fun onCreate() {
        super.onCreate()
        if (scopePatterns.isNotEmpty()) scopeFilter.setPatterns(scopePatterns)
        intercept.onEnabledChange = { applyProxyMode() }
        // Match & replace rules are kept across restarts: load first, then start saving every change.
        intercept.rules.loadJson(prefs.getString("rules", "") ?: "")
        intercept.rules.onChange = { prefs.edit().putString("rules", intercept.rules.toJson()).apply() }
        adBlocker.enabled = adblockEnabled()
        adBlocker.loadAsync()
        browserEngine.start(engineScope)
        if (devtoolsEnabled()) {
            // The flag is per process and starts false, so a restart must re-apply what the user left switched on.
            main.post { runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(true) } }
            engineScope.launch { runCatching { cdpForwarder.start(CDP_PORT) } }
        }
        engineScope.launch { runCatching { bridge.start() } }
        Log.i(TAG, "AlpBrowser proxy on 127.0.0.1:$PROXY_PORT, control API starting on 127.0.0.1:$CONTROL_PORT")
    }

    private var keepAliveStarted = false

    /** Starts [BrowserKeepAliveService] the first time there is a page to keep alive (a no-op afterwards). */
    fun startKeepAliveOnce(context: android.content.Context) {
        if (keepAliveStarted) return
        keepAliveStarted = true
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, BrowserKeepAliveService::class.java))
        }
    }

    /** Runs [script] in the live page and blocks the (bridge) caller until the result arrives. Never on the UI thread. */
    private fun evaluateBrowserJsBlocking(script: String): String {
        val webView = browserWebViewHolder.current ?: return "\"error: no page open yet\""
        var result = "null"
        val latch = CountDownLatch(1)
        main.post {
            runCatching {
                webView.evaluateJavascript(script) { r -> result = r ?: "null"; latch.countDown() }
            }.onFailure { result = "\"error: ${it.message}\""; latch.countDown() }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) return "\"error: timed out\""
        return result
    }

    /** Replays a request directly (not through the proxy, so no duplicate History entry) — the repeater. */
    fun httpResend(req: BrowserBridge.ResendRequest): BrowserBridge.ResendResponse {
        val future = resendPool.submit<BrowserBridge.ResendResponse> {
            runCatching {
                val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = req.method
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    for ((n, v) in req.headers) {
                        if (n.equals("Content-Length", true) || n.equals("Host", true) || n.equals("Accept-Encoding", true)) continue
                        runCatching { setRequestProperty(n, v) }
                    }
                    if (req.body != null && req.method !in setOf("GET", "HEAD")) {
                        doOutput = true
                        outputStream.use { it.write(req.body) }
                    }
                }
                val code = conn.responseCode
                val headers = conn.headerFields.entries.filter { it.key != null }.flatMap { e -> e.value.map { e.key to it } }
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                // Cap the replayed body so a huge response can't OOM the browser process (held in memory, base64'd to the agent).
                val body = stream?.use { readCapped(it, MAX_RESEND_BODY) }
                conn.disconnect()
                BrowserBridge.ResendResponse(code, headers, body)
            }.getOrElse { BrowserBridge.ResendResponse(0, emptyList(), null, error = it.message ?: "resend failed") }
        }
        return runCatching { future.get(30, TimeUnit.SECONDS) }
            .getOrElse { BrowserBridge.ResendResponse(0, emptyList(), null, error = "resend timed out") }
    }

    /** Clears cookies / cache / web storage on the live WebView, on the main thread. [what] = cookies|cache|storage|all. */
    fun clearBrowserData(what: String) {
        main.post {
            runCatching {
                if (what == "cookies" || what == "all") {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                }
                if (what == "storage" || what == "all") android.webkit.WebStorage.getInstance().deleteAllData()
                if (what == "cache" || what == "all") browserWebViewHolder.allWebViews.firstOrNull()?.clearCache(true)
            }
        }
    }

    /** Reads at most [max] bytes from [input] (API-safe, unlike InputStream.readNBytes which needs API 33+). */
    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "AlpBrowser"
        const val PROXY_PORT = 8890
        const val CONTROL_PORT = 8899
        const val CDP_PORT = 9222
        private const val MAX_RESEND_BODY = 10 * 1024 * 1024
        /** CN of our MITM root CA (see core-proxy TlsCa.selfSignedCa) — the SSL-error fallback check. */
        const val CA_CN = "Ronin Proxy CA"
    }
}
