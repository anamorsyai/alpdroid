package com.alpdroid.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * A small local HTTP control API for programs running inside the terminal (opencode, Claude
 * Code, scripts) — the `alpctl` command in the guest is a thin client for it. Bound to loopback
 * only and guarded by a per-install bearer token; off unless the user turns it on. Everything
 * that touches UI or settings is marshalled through [Host] so it runs on the main thread, and
 * the sensitive settings ask the user to confirm on screen first.
 */
class AgentBridge(private val app: AlpineTermApp) {

    interface Host {
        fun settingsJson(): JSONObject
        /** "ok", or an error message. */
        fun setSetting(key: String, value: String): String
        fun addShortcut(label: String, cmd: String): String
        fun newTab(label: String?): String
        fun selectTab(id: Int): Boolean
        fun closeTab(id: Int): Boolean
        fun clipboardGet(): String
        fun clipboardSet(text: String)
        fun notify(title: String, text: String)
        fun toast(text: String)
        fun openUrl(url: String): Boolean
        fun devicesJson(): JSONObject
        fun githubStatus(): JSONObject
        /** Null when not signed in or the user hasn't allowed agents to use the token. */
        fun githubToken(): String?
        fun <T> onMain(block: () -> T): T
    }

    @Volatile var host: Host? = null
    @Volatile private var token: String = ""
    /** The port actually bound (preferred or ephemeral fallback) — 0 when not running.
     *  The guest reads it fresh from /etc/alpdroid/bridge on every alpctl call. */
    @Volatile var actualPort: Int = 0
        private set
    private var server: ServerSocket? = null
    // Fixed small pool, not cached: request handlers block up to 10s in onMain(), so a
    // connection flood against the unbounded pool used to spawn threads without limit.
    private val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "agent-bridge").apply { isDaemon = true } }

    val isRunning: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(port: Int, token: String): Boolean {
        this.token = token
        if (isRunning) return true
        return runCatching {
            val loopback = InetAddress.getByName("127.0.0.1")
            val ss = try {
                ServerSocket(port, 16, loopback)
            } catch (e: java.net.BindException) {
                // Fixed-port squat: any app with INTERNET can bind 127.0.0.1:port first and
                // then receive our real token via the guest's alpctl calls. Never serve the
                // token to a port we don't hold — take an ephemeral one instead; the guest
                // reads the actual port fresh from /etc/alpdroid/bridge on every call.
                ServerSocket(0, 16, loopback)
            }
            actualPort = ss.localPort
            server = ss
            Thread({
                while (!ss.isClosed) {
                    val sock = runCatching { ss.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { handle(sock) }; runCatching { sock.close() } }
                }
            }, "agent-bridge-accept").apply { isDaemon = true }.start()
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        actualPort = 0
    }

    fun updateToken(token: String) { this.token = token }

    companion object {
        private const val MAX_HEADERS = 100
        private val TAB_ROUTE = Regex("^/v1/tabs/(\\d+)/(send|screen|select|close)$")
    }

    private fun readLine(input: InputStream, deadlineNs: Long): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            // Absolute deadline for the whole header phase: the per-read socket timeout alone let a client that
            // sends one byte every few seconds hold a handler thread (there are only 8) indefinitely.
            if (System.nanoTime() > deadlineNs) return null
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun handle(sock: Socket) {
        // Pre-auth phase: short per-read timeout and an absolute 8 s limit to deliver the request line and headers.
        sock.soTimeout = 3_000
        val headerDeadline = System.nanoTime() + 8_000_000_000L
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = readLine(input, headerDeadline) ?: return
        val parts = requestLine.split(" ")
        // Only what this tiny API speaks; anything else (a stray browser probe, garbage)
        // is dropped before it reaches routing or auth handling.
        if (parts.size != 3 || (parts[0] != "GET" && parts[0] != "POST")) return
        if (!parts[1].startsWith("/")) return
        val headers = HashMap<String, String>()
        var headerCount = 0
        while (true) {
            val line = readLine(input, headerDeadline) ?: return
            if (line.isEmpty()) break
            // Unbounded header count (only each line was length-capped) let one connection
            // pile up HashMap entries indefinitely.
            if (++headerCount > MAX_HEADERS) return
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        // Chunked bodies are never read (only content-length is): a chunked request would be
        // silently treated as an empty-JSON one and acted on. Reject instead of misreading.
        if (headers.containsKey("transfer-encoding")) return respond(sock, 400, error("chunked bodies not supported"))
        val supplied = (headers["authorization"]?.removePrefix("Bearer ") ?: headers["x-alp-token"] ?: "").trim()
        // Authenticated before a single body byte is read: previously any loopback client
        // (any app on the phone, no token) could force up to 1MB of allocation + thread time.
        if (token.isEmpty() || !MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
            return respond(sock, 401, error("missing or wrong token"))
        }
        sock.soTimeout = 20_000 // authenticated: allow the body and slower handlers

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > 1_000_000) return respond(sock, 413, error("body too large"))
        val body = if (length > 0) {
            val bytes = ByteArray(length)
            var off = 0
            while (off < length) { val n = input.read(bytes, off, length - off); if (n < 0) break; off += n }
            String(bytes, 0, off, Charsets.UTF_8)
        } else ""

        val target = parts[1]
        val path = target.substringBefore('?')
        // Malformed % escapes throw outside the route try/catch — fail the request, not the thread.
        val query = runCatching {
            target.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        }.getOrElse { return respond(sock, 400, error("bad query string")) }
        val json = runCatching { if (body.isBlank()) JSONObject() else JSONObject(body) }.getOrElse { return respond(sock, 400, error("body must be JSON")) }

        val (code, out) = try {
            route(host, parts[0].uppercase(), path, query, json)
        } catch (e: Exception) {
            500 to error(e.message ?: e.javaClass.simpleName)
        }
        respond(sock, code, out)
    }

    private fun error(msg: String) = JSONObject().put("error", msg)

    private fun respond(sock: Socket, wantedCode: Int, body: JSONObject) {
        // Only "who are you" (401) and "app not ready" (503) use error statuses. Everything else is a
        // 200 whose body carries {"error": "..."}: busybox wget throws the body away on any 4xx/5xx, so
        // alpctl (and an agent reading its output) would otherwise never see *why* something failed.
        val code = if (wantedCode == 401 || wantedCode == 503) wantedCode else 200
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 503 -> "Service Unavailable"; else -> "Error" }
        sock.getOutputStream().apply {
            write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun tabJson(t: TerminalTab, active: Boolean) = JSONObject()
        .put("id", t.id).put("label", t.label ?: JSONObject.NULL).put("active", active)
        .put("backend", t.backendLabel).put("alive", t.session.isAlive())

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Runs [block] on the main thread and waits — works whether or not an Activity exists. */
    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        val out = java.util.concurrent.atomic.AtomicReference<Result<T>>()
        mainHandler.post { out.set(runCatching(block)); latch.countDown() }
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("app is busy")
        return out.get().getOrThrow()
    }

    private val noUi = 503 to JSONObject().put("error", "the AlpDroid screen isn't open — open the app and retry (tabs, notify, toast and clipboard still work without it)")

    private fun route(h: Host?, method: String, path: String, q: Map<String, String>, body: JSONObject): Pair<Int, JSONObject> {
        val ok = JSONObject().put("ok", true)
        fun str(k: String) = body.optString(k, "").ifEmpty { q[k] ?: "" }
        TAB_ROUTE.matchEntire(path)?.let { m ->
            // toIntOrNull: \d+ can exceed Int range (e.g. 9999999999999) — toInt() would throw
            // a NumberFormatException, surfacing as a 500 with leaked exception text.
            val id = m.groupValues[1].toIntOrNull() ?: return 404 to error("no such tab")
            val tab = onMain { app.tabs.firstOrNull { it.id == id } } ?: return 404 to error("no such tab")
            return when (m.groupValues[2]) {
                "send" -> {
                    // Cap: a 1MB single shot would freeze the UI feeding the pty/emulator at once.
                    val text = body.optString("text", "").take(100_000)
                    val bytes = (text + if (body.optBoolean("enter", true)) "\r" else "").toByteArray(Charsets.UTF_8)
                    tab.session.writeAsync(bytes)
                    200 to ok
                }
                "screen" -> {
                    // Clamp: unbounded lines= would force tailText() into a giant String → OOM.
                    val lines = ((q["lines"] ?: body.optString("lines")).toIntOrNull() ?: 200).coerceIn(1, 2000)
                    val text = tab.emulator.tailText(lines)
                    200 to JSONObject().put("text", text).put("cwd", JSONObject.NULL)
                }
                "select" -> if (h == null) noUi else 200 to JSONObject().put("ok", h.selectTab(id))
                else -> if (h == null) noUi else 200 to JSONObject().put("ok", h.closeTab(id))
            }
        }
        val tabsJson = { JSONArray(onMain { app.tabs.mapIndexed { i, t -> tabJson(t, i == app.activeTabIndex) } }) }
        return when {
            path == "/v1/ping" -> 200 to JSONObject().put("ok", true).put("app", "AlpDroid").put("screen_open", h != null)
            path == "/v1/tabs" && method == "GET" -> 200 to JSONObject().put("tabs", tabsJson())
            path == "/v1/clipboard" && method == "GET" -> 200 to JSONObject().put("text", onMain {
                (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() ?: ""
            })
            path == "/v1/clipboard" -> { onMain { (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("AlpDroid", body.optString("text", "").take(256_000))) }; 200 to ok }
            path == "/v1/notify" -> { OperationNotifications.alert(app, OperationNotifications.newId(), str("title").ifEmpty { "AlpDroid" }.take(80), str("text").take(300)); 200 to ok }
            path == "/v1/toast" -> { mainHandler.post { android.widget.Toast.makeText(app, str("text").take(300), android.widget.Toast.LENGTH_LONG).show() }; 200 to ok }
            h == null -> {
                // No UI host alive — the settings-shaped routes that only need the Application
                // context still work; GitHub routes moved BEFORE this gate for the same reason.
                when {
                    path == "/v1/ping" -> 200 to JSONObject().put("ok", true).put("app", "AlpDroid").put("screen_open", false)
                    else -> noUi
                }
            }
            path == "/v1/state" -> 200 to JSONObject().put("settings", h.settingsJson()).put("tabs", tabsJson()).put("github", h.githubStatus())
            path == "/v1/settings" && method == "GET" -> 200 to h.settingsJson()
            path == "/v1/settings" -> {
                val key = str("key"); val value = str("value")
                if (key.isEmpty()) 400 to error("need key and value")
                else h.setSetting(key, value).let { r -> if (r == "ok") 200 to ok else 400 to error(r) }
            }
            path == "/v1/shortcuts" -> h.addShortcut(str("label"), str("cmd")).let { r -> if (r == "ok") 200 to ok else 400 to error(r) }
            path == "/v1/tabs" -> 200 to JSONObject().put("ok", true).put("status", h.newTab(str("label").ifEmpty { null }?.take(100)))
            path == "/v1/open" -> if (h.openUrl(str("url"))) 200 to ok else 400 to error("only http(s) URLs")
            // GitHub routes work without the UI host: the token lives in the app Keystore,
            // reachable from the Application context alone. Previously agents lost git auth
            // whenever the screen host was detached (update, kill) even with the option on.
            path == "/v1/github" -> {
                200 to JSONObject()
                    .put("signed_in", GitHubTokenReader.signedIn(app))
                    .put("login", GitHubAuth.login(app) ?: JSONObject.NULL)
                    .put("agents_may_use_token", GitHubTokenReader.allowed(app))
            }
            path == "/v1/github/token" -> GitHubTokenReader.token(app)?.let { 200 to JSONObject().put("token", it) }
                ?: (403 to error("not signed in to GitHub, or 'Let agents use my GitHub token' is off"))
            else -> 404 to error("unknown endpoint")
        }
    }
}
