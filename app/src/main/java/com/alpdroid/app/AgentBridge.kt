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
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "agent-bridge").apply { isDaemon = true } }

    val isRunning: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(port: Int, token: String): Boolean {
        this.token = token
        if (isRunning) return true
        return runCatching {
            val ss = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
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
    }

    fun updateToken(token: String) { this.token = token }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 20_000
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > 1_000_000) return respond(sock, 413, error("body too large"))
        val body = if (length > 0) {
            val bytes = ByteArray(length)
            var off = 0
            while (off < length) { val n = input.read(bytes, off, length - off); if (n < 0) break; off += n }
            String(bytes, 0, off, Charsets.UTF_8)
        } else ""

        val supplied = (headers["authorization"]?.removePrefix("Bearer ") ?: headers["x-alp-token"] ?: "").trim()
        if (token.isEmpty() || !MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
            return respond(sock, 401, error("missing or wrong token"))
        }

        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
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

    private val noUi = 503 to JSONObject().put("error", "the AlpineTerm screen isn't open — open the app and retry (tabs, notify, toast and clipboard still work without it)")

    private fun route(h: Host?, method: String, path: String, q: Map<String, String>, body: JSONObject): Pair<Int, JSONObject> {
        val ok = JSONObject().put("ok", true)
        fun str(k: String) = body.optString(k, "").ifEmpty { q[k] ?: "" }
        Regex("^/v1/tabs/(\\d+)/(send|screen|select|close)$").matchEntire(path)?.let { m ->
            val id = m.groupValues[1].toInt()
            val tab = onMain { app.tabs.firstOrNull { it.id == id } } ?: return 404 to error("no such tab")
            return when (m.groupValues[2]) {
                "send" -> {
                    val text = body.optString("text", "")
                    val bytes = (text + if (body.optBoolean("enter", true)) "\r" else "").toByteArray(Charsets.UTF_8)
                    tab.session.writeAsync(bytes)
                    200 to ok
                }
                "screen" -> {
                    val lines = (q["lines"] ?: body.optString("lines")).toIntOrNull() ?: 200
                    val text = tab.emulator.fullText().trimEnd().lines().takeLast(lines.coerceIn(1, 5000)).joinToString("\n")
                    200 to JSONObject().put("text", text).put("cwd", JSONObject.NULL)
                }
                "select" -> if (h == null) noUi else 200 to JSONObject().put("ok", h.selectTab(id))
                else -> if (h == null) noUi else 200 to JSONObject().put("ok", h.closeTab(id))
            }
        }
        val tabsJson = { JSONArray(onMain { app.tabs.mapIndexed { i, t -> tabJson(t, i == app.activeTabIndex) } }) }
        return when {
            path == "/v1/ping" -> 200 to JSONObject().put("ok", true).put("app", "AlpineTerm").put("screen_open", h != null)
            path == "/v1/tabs" && method == "GET" -> 200 to JSONObject().put("tabs", tabsJson())
            path == "/v1/clipboard" && method == "GET" -> 200 to JSONObject().put("text", onMain {
                (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() ?: ""
            })
            path == "/v1/clipboard" -> { onMain { (app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("AlpineTerm", body.optString("text", ""))) }; 200 to ok }
            path == "/v1/notify" -> { OperationNotifications.alert(app, OperationNotifications.newId(), str("title").ifEmpty { "AlpineTerm" }.take(80), str("text").take(300)); 200 to ok }
            path == "/v1/toast" -> { mainHandler.post { android.widget.Toast.makeText(app, str("text").take(300), android.widget.Toast.LENGTH_LONG).show() }; 200 to ok }
            h == null -> noUi
            path == "/v1/state" -> 200 to JSONObject().put("settings", h.settingsJson()).put("tabs", tabsJson()).put("github", h.githubStatus())
            path == "/v1/settings" && method == "GET" -> 200 to h.settingsJson()
            path == "/v1/settings" -> {
                val key = str("key"); val value = str("value")
                if (key.isEmpty()) 400 to error("need key and value")
                else h.setSetting(key, value).let { r -> if (r == "ok") 200 to ok else 400 to error(r) }
            }
            path == "/v1/shortcuts" -> h.addShortcut(str("label"), str("cmd")).let { r -> if (r == "ok") 200 to ok else 400 to error(r) }
            path == "/v1/tabs" -> 200 to JSONObject().put("ok", true).put("status", h.newTab(str("label").ifEmpty { null }))
            path == "/v1/open" -> if (h.openUrl(str("url"))) 200 to ok else 400 to error("only http(s) URLs")
            path == "/v1/devices" -> 200 to h.devicesJson()
            path == "/v1/github" -> 200 to h.githubStatus()
            path == "/v1/github/token" -> h.githubToken()?.let { 200 to JSONObject().put("token", it) }
                ?: (403 to error("not signed in to GitHub, or 'Let agents use my GitHub token' is off"))
            else -> 404 to error("unknown endpoint")
        }
    }
}
