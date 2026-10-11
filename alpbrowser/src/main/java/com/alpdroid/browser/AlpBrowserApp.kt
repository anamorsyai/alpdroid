package com.alpdroid.browser

import android.app.Application
import android.os.Handler
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
    val extensions by lazy { ExtensionStore(java.io.File(filesDir, "extensions")) }
    val extensionInjector by lazy { ExtensionInjector(extensions) { browserWebViewHolder.current } }

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
    fun devtoolsEnabled(): Boolean = prefs.getBoolean("devtools", false)
    fun setDevtoolsEnabled(on: Boolean) = prefs.edit().putBoolean("devtools", on).apply()

    /** "bugbounty" (intercept + rules + scope, default) or "webdev" (console + eval + cache control). */
    fun mode(): String = prefs.getString("mode", "bugbounty") ?: "bugbounty"
    fun setMode(m: String) {
        prefs.edit().putString("mode", m).apply()
        if (m == "webdev") intercept.enabled = false // web dev watches, it does not pause traffic
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
            respectScope = true, // empty scope = capture everything; set a scope to narrow it (bug-bounty)
            tlsMitmProvider = { tlsMitm },
            interceptor = intercept.interceptor,
        )
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
        adBlocker.enabled = adblockEnabled()
        adBlocker.loadAsync()
        browserEngine.start(engineScope)
        engineScope.launch { runCatching { bridge.start() } }
        Log.i(TAG, "AlpBrowser proxy on 127.0.0.1:$PROXY_PORT, control API starting on 127.0.0.1:$CONTROL_PORT")
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
                if (what == "cache" || what == "all") browserWebViewHolder.current?.clearCache(true)
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
        private const val MAX_RESEND_BODY = 10 * 1024 * 1024
        /** CN of our MITM root CA (see core-proxy TlsCa.selfSignedCa) — the SSL-error fallback check. */
        const val CA_CN = "Ronin Proxy CA"
    }
}
