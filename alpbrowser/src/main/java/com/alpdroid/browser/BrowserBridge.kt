package com.alpdroid.browser

import com.alpdroid.proxy.HttpTransaction
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import com.alpdroid.proxy.TransactionStore
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent-facing control API: a tiny HTTP server on loopback (token-authenticated, same idea as AlpDroid's own
 * AgentBridge) that gives the AlpDroid agent — running inside Alpine, reachable over 127.0.0.1 — full control of the
 * browser and its decrypted traffic. It is deliberately dependency-light (raw sockets + org.json) and takes the few
 * Android-touching actions as callbacks, so the routing and protocol are plain JVM and unit-testable.
 *
 * Endpoints (all under /v1, bodies and replies are JSON; auth via `Authorization: Bearer <token>` or `?token=`):
 *  - GET  /ping                      liveness + the proxy port to point clients at
 *  - GET  /ca                        the MITM root CA (PEM) so Alpine tools can trust intercepted HTTPS
 *  - GET  /history[?host=&method=&status=&contains=&limit=]   captured request/response list (summaries)
 *  - GET  /history/<id>              one transaction in full, bodies base64 + decoded text when printable
 *  - GET  /intercept                 breakpoint state + everything currently parked
 *  - POST /intercept                 {enabled,hostFilter,methodFilter} turn the breakpoint on/off and scope it
 *  - POST /intercept/resolve         {holdId,action:forward|drop|replace,...edits} release one parked message
 *  - POST /navigate                  {url} drive the page
 *  - POST /eval                      {js} run JavaScript in the live page, returns its result
 *  - POST /resend                    {method,url,headers,body} replay a request, returns the response (repeater)
 */
/** What the control API needs from the browser's tabs (implemented by the app, on the main thread). */
interface TabsHost {
    fun list(): List<TabInfo>
    /** Opens a tab and returns its id (after the page has loaded when [wait]), or null when the tab limit is reached. */
    fun open(url: String, wait: Boolean = false, timeoutMs: Long = 20_000): Int?
    fun switchTo(id: Int): Boolean
    fun close(id: Int): Boolean
}

class BrowserBridge(
    private val token: String,
    private val store: TransactionStore,
    private val intercept: InterceptController,
    private val proxyPort: () -> Int,
    private val caPem: () -> String?,
    private val onNavigate: (String) -> Unit,
    private val onEvalJs: (String) -> String,
    private val onResend: (ResendRequest) -> ResendResponse,
    private val scopeGet: () -> List<String>,
    private val scopeSet: (List<String>) -> Unit,
    private val console: ConsoleBuffer,
    private val modeGet: () -> String,
    private val modeSet: (String) -> Unit,
    private val onClear: (String) -> Unit,
    private val preferredPort: Int = 8899,
    private val captureGet: () -> Boolean = { true },
    private val captureSet: (Boolean) -> Unit = {},
    private val tabs: TabsHost? = null,
    private val page: PageControl? = null,
    private val cdp: CdpControl? = null,
    private val appVersion: String = "",
    /** True while open local access is on: programs on this phone may skip the token (see [LocalAccess]). */
    private val openAccess: () -> Boolean = { false },
) {
    data class ResendRequest(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray?)
    data class ResendResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray?, val error: String? = null)

    @Volatile private var server: ServerSocket? = null

    // Bounded worker pool: 127.0.0.1 is shared by every app on the device, so a hostile local app
    // could otherwise flood the accept loop and spawn unbounded threads. Excess connections are
    // rejected (socket closed) rather than queued without limit. The accept loop runs on its own
    // thread so a full worker pool never stops us from accepting (and rejecting) new connections.
    private val workers = ThreadPoolExecutor(
        2, 16, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "browser-bridge").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    val boundPort: Int get() = server?.localPort ?: -1

    /** Binds loopback-only. Tries [preferredPort] first, then an ephemeral port, so two installs never collide fatally. */
    @Synchronized
    fun start() {
        if (server != null) return
        val loopback = InetAddress.getByName("127.0.0.1")
        val s = runCatching { ServerSocket(preferredPort, 64, loopback) }.getOrElse { ServerSocket(0, 64, loopback) }
        server = s
        Thread({
            while (!s.isClosed) {
                // Only a closed listener ends the loop; a transient per-accept error is skipped, not fatal.
                val c = try { s.accept() } catch (e: Exception) { if (s.isClosed) break else continue }
                try { workers.execute { runCatching { handle(c) } } }
                catch (_: RejectedExecutionException) { runCatching { c.close() } }
            }
        }, "browser-bridge-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** One header/request line read as bytes (ISO-8859-1), so a body is never confused with characters. */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 16 * 1024) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private fun handle(sock: Socket) {
        sock.use {
            sock.soTimeout = 15_000
            val input = java.io.BufferedInputStream(sock.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size != 3 || (parts[0] != "GET" && parts[0] != "POST")) { respond(sock.getOutputStream(), 400, error("bad request")); return }
            val method = parts[0]
            var authHeader: String? = null
            var contentLength = 0L
            val hdrs = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                hdrs[name] = value
                if (name == "authorization") authHeader = value
                if (name == "content-length") contentLength = value.toLongOrNull() ?: 0L
            }
            val rawPath = parts[1]
            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val query = if (qIdx >= 0) parseQuery(rawPath.substring(qIdx + 1)) else emptyMap()

            // Token: Bearer header or ?token=. Constant-time-ish compare.
            val presented = authHeader?.removePrefix("Bearer ")?.trim() ?: query["token"] ?: ""
            if (!tokenOk(presented) && !(openAccess() && LocalAccess.allowed(hdrs))) { respond(sock.getOutputStream(), 401, error("unauthorized")); return }

            if (contentLength > MAX_BODY_BYTES) { respond(sock.getOutputStream(), 400, error("request body too large")); return }
            // Exactly Content-Length BYTES, decoded as UTF-8 afterwards: counting characters instead would hang on (or cut)
            // any script containing non-ASCII text, which arrives as more bytes than characters.
            val bodyBytes = ByteArray(contentLength.toInt())
            var read = 0
            while (read < bodyBytes.size) { val n = input.read(bodyBytes, read, bodyBytes.size - read); if (n < 0) break; read += n }
            val bodyText = String(bodyBytes, 0, read, Charsets.UTF_8)
            val body = if (bodyText.isBlank()) JSONObject() else try { JSONObject(bodyText) } catch (e: Exception) {
                respond(sock.getOutputStream(), 400, error("body is not valid JSON: ${e.message}")); return
            }

            val raw = try { rawRoute(method, path, query) } catch (e: Throwable) { Raw(500, "application/json", error(e.message ?: "error").toString().toByteArray()) }
            if (raw != null) { respondRaw(sock.getOutputStream(), raw); return }
            val (code, json) = try { route(method, path, query, body) } catch (e: Throwable) {
                // Page commands answer 200 with {"ok":false,...} so the client can print the reason; a bare HTTP error would only show as "request failed".
                val automation = path == "/v1/eval" || path == "/v1/act" || path == "/v1/navigate" || path == "/v1/waitnav" || path == "/v1/cookies" || path == "/v1/cdp"
                (if (automation) 200 else 500) to error("${e.javaClass.simpleName}: ${e.message ?: "error"}")
            }
            respond(sock.getOutputStream(), code, json)
        }
    }

    /** [v] as JSON text. Written out because Android's org.json has no `JSONObject.valueToString` (the json.org library the unit tests use does). */
    private fun jsonText(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(v)
        else -> v.toString()   // JSONObject, JSONArray, Number and Boolean all print as JSON
    }

    private class Raw(val code: Int, val type: String, val bytes: ByteArray)

    private fun respondRaw(out: OutputStream, r: Raw) {
        val head = "HTTP/1.1 ${r.code} ${reason(r.code)}\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching { out.write(head.toByteArray(Charsets.US_ASCII)); out.write(r.bytes); out.flush() }
    }

    private fun text(t: String) = Raw(200, "text/plain; charset=utf-8", t.toByteArray(Charsets.UTF_8))

    /** Routes whose answer is not JSON: screenshots, and decoded messages / bodies saved to disk. */
    private fun rawRoute(method: String, path: String, q: Map<String, String>): Raw? {
        if (method != "GET") return null
        if (path == "/v1/shot") {
            val pc = page ?: return Raw(200, "application/json", error("page control is not available").toString().toByteArray())
            val jpeg = q["format"].equals("jpeg", true) || q["format"].equals("jpg", true)
            val (bytes, err) = pc.screenshot(q["selector"]?.takeIf { it.isNotBlank() }, q["fullPage"] == "1" || q["fullPage"] == "true", jpeg)
            return if (bytes != null) Raw(200, if (jpeg) "image/jpeg" else "image/png", bytes)
            else Raw(200, "application/json", render(err ?: error("screenshot failed")).toByteArray())
        }
        if (path == "/v1/history" && q["fmt"] == "lines") {
            val lim = q["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: Int.MAX_VALUE
            val skip = ((q["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1) - 1).toLong() * (if (lim == Int.MAX_VALUE) 0 else lim)
            val lines = filteredHistory(q).drop(skip.toInt()).take(lim).joinToString("\n") { tx ->
                "${tx.id} ${tx.method} ${tx.responseStatus ?: "-"} ${tx.durationMs ?: "-"}ms ${tx.host}${tx.path}"
            }
            return text(if (lines.isEmpty()) "" else lines + "\n")
        }
        if (path == "/v1/har") return Raw(200, "application/json", Har.build(filteredHistory(q).asReversed(), appVersion).toString().toByteArray(Charsets.UTF_8))
        val m = Regex("^/v1/history/([A-Za-z0-9-]+)/(request|response|meta|body)(?:/(request|response))?$").find(path) ?: return null
        val tx = store.transactions.value.firstOrNull { it.id == m.groupValues[1] } ?: return Raw(404, "application/json", error("no such transaction").toString().toByteArray())
        return when (m.groupValues[2]) {
            "request" -> text(Har.rawRequest(tx))
            "response" -> text(Har.rawResponse(tx))
            "meta" -> Raw(200, "application/json", Har.meta(tx).toString().toByteArray())
            else -> Raw(200, "application/octet-stream", (if (m.groupValues[3] == "request") tx.requestBody else tx.responseBody) ?: ByteArray(0))
        }
    }

    private fun tokenOk(presented: String): Boolean {
        if (presented.length != token.length) return false
        var diff = 0
        for (i in token.indices) diff = diff or (presented[i].code xor token[i].code)
        return diff == 0
    }

    private fun route(method: String, path: String, q: Map<String, String>, body: JSONObject): Pair<Int, JSONObject> {
        when {
            path == "/v1/ping" -> return 200 to JSONObject().put("ok", true).put("app", "AlpBrowser").put("version", appVersion).put("proxyPort", proxyPort())
            path == "/v1/ca" && method == "GET" -> return 200 to JSONObject().put("pem", caPem() ?: "")
            path == "/v1/history" && method == "GET" -> return 200 to historyList(q)
            path.startsWith("/v1/history/") && method == "GET" -> return historyOne(path.removePrefix("/v1/history/"))
            path == "/v1/intercept" && method == "GET" -> return 200 to interceptState()
            path == "/v1/intercept" && method == "POST" -> return setIntercept(body)
            path == "/v1/intercept/resolve" && method == "POST" -> return resolveHold(body)
            path == "/v1/navigate" && method == "POST" -> {
                val url = body.optString("url"); if (url.isBlank()) return 400 to error("url required")
                val pc = page
                if (body.optBoolean("wait") && pc != null) return 200 to pc.navigate(url, true, body.optString("state", "load"), body.optLong("timeout", 30_000))
                onNavigate(url); return 200 to ok()
            }
            path == "/v1/waitnav" && method == "POST" -> {
                val pc = page ?: return 404 to error("page control is not available")
                return 200 to pc.waitNav(body.optString("state", "load"), body.optLong("timeout", 30_000))
            }
            path == "/v1/act" && method == "POST" -> {
                val pc = page ?: return 404 to error("page control is not available")
                if (body.optString("cmd").isBlank()) return 400 to error("cmd required")
                return 200 to pc.command(body)
            }
            path == "/v1/cookies" && method == "GET" -> {
                val pc = page ?: return 404 to error("page control is not available")
                return 200 to pc.cookies(q["domain"]?.takeIf { it.isNotBlank() })
            }
            path == "/v1/cookies" && method == "POST" -> {
                val pc = page ?: return 404 to error("page control is not available")
                val name = body.optString("name"); if (name.isBlank()) return 400 to error("name required")
                return 200 to pc.setCookie(CookieSpec(
                    name = name, value = body.optString("value"),
                    domain = body.optStringOrNull("domain")?.takeIf { it.isNotBlank() }, path = body.optStringOrNull("path")?.takeIf { it.isNotBlank() },
                    secure = body.optBoolean("secure"), httpOnly = body.optBoolean("httpOnly"), sameSite = body.optStringOrNull("sameSite")?.takeIf { it.isNotBlank() },
                    expires = if (body.has("expires")) body.optLong("expires") else null, maxAge = if (body.has("maxAge")) body.optLong("maxAge") else null,
                ))
            }
            path == "/v1/cdp" && method == "GET" -> {
                val c = cdp ?: return 404 to error("cdp is not available")
                return 200 to c.status()
            }
            path == "/v1/cdp" && method == "POST" -> {
                val c = cdp ?: return 404 to error("cdp is not available")
                return 200 to c.set(body.optBoolean("enabled", true), if (body.has("port")) body.optInt("port") else null)
            }
            path == "/v1/eval" && method == "POST" -> {
                val js = body.optString("js"); if (js.isBlank()) return 400 to error("js required")
                val pc = page
                // Through the page-automation path whenever it exists: it waits for the script and reports a page that
                // is not ready, where a raw evaluateJavascript call answers a bare null in the middle of a navigation.
                if (pc != null) {
                    val r = pc.eval(js, body.optBoolean("await"), body.optLong("timeout", 30_000))
                    if (!r.optBoolean("ok", true)) return 200 to r
                    // Same shape as before: "result" is the value as JSON text.
                    return 200 to JSONObject().put("ok", true).put("result", jsonText(r.opt("result")))
                }
                return 200 to JSONObject().put("result", onEvalJs(js))
            }
            path == "/v1/resend" && method == "POST" -> return resend(body)
            path == "/v1/scope" && method == "GET" -> return 200 to JSONObject().put("patterns", JSONArray(scopeGet()))
            path == "/v1/scope" && method == "POST" -> {
                val pats = (body.optJSONArray("patterns") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
                scopeSet(pats); return 200 to JSONObject().put("patterns", JSONArray(scopeGet()))
            }
            path == "/v1/rules" && method == "GET" -> return 200 to rulesJson()
            path == "/v1/rules" && method == "POST" -> return addRule(body)
            path == "/v1/rules/delete" && method == "POST" -> {
                val id = body.optString("id"); return if (intercept.rules.remove(id)) 200 to ok() else 404 to error("no such rule")
            }
            path == "/v1/rules/enable" && method == "POST" -> {
                val id = body.optString("id")
                return if (intercept.rules.setEnabled(id, body.optBoolean("enabled", true))) 200 to ok() else 404 to error("no such rule")
            }
            path == "/v1/console" && method == "GET" -> return 200 to consoleJson(q["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200)
            path == "/v1/console/clear" && method == "POST" -> { console.clear(); return 200 to ok() }
            path == "/v1/mode" && method == "GET" -> return 200 to modeJson()
            path == "/v1/mode" && method == "POST" -> {
                val m = body.optString("mode"); if (m != "bugbounty" && m != "webdev") return 400 to error("mode must be bugbounty or webdev")
                modeSet(m); return 200 to modeJson()
            }
            path == "/v1/tabs" && method == "GET" -> {
                val host = tabs ?: return 404 to error("tabs are not available")
                val arr = JSONArray()
                host.list().forEach {
                    arr.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("active", it.active)
                        .put("loading", it.loading).put("lastActiveAt", it.lastActiveAt))
                }
                return 200 to JSONObject().put("tabs", arr)
            }
            path == "/v1/tabs" && method == "POST" -> {
                val host = tabs ?: return 404 to error("tabs are not available")
                return when (body.optString("action")) {
                    "new" -> host.open(body.optString("url"), body.optBoolean("wait", true), body.optLong("timeout", 20_000)).let { id -> if (id == null) 409 to error("tab limit reached") else 200 to JSONObject().put("ok", true).put("id", id) }
                    "switch" -> if (host.switchTo(body.optInt("id", -1))) 200 to ok() else 404 to error("no such tab")
                    "close" -> if (host.close(body.optInt("id", -1))) 200 to ok() else 404 to error("no such tab")
                    else -> 400 to error("action must be new, switch or close")
                }
            }
            path == "/v1/capture" && method == "GET" -> return 200 to captureJson()
            path == "/v1/capture" && method == "POST" -> {
                if (!body.has("enabled")) return 400 to error("enabled required")
                captureSet(body.optBoolean("enabled")); return 200 to captureJson()
            }
            path == "/v1/clear" && method == "POST" -> { onClear(body.optString("what", "all")); return 200 to ok() }
            else -> return 404 to error("unknown endpoint")
        }
    }

    /** Captured traffic, newest first, narrowed by host / method / status / contains / since (ISO time). */
    private fun filteredHistory(q: Map<String, String>): List<HttpTransaction> {
        val host = q["host"]; val m = q["method"]; val status = q["status"]?.toIntOrNull(); val contains = q["contains"]
        val since = q["since"]?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        return store.transactions.value.asReversed()
            .filter { host == null || it.host.contains(host, true) }
            .filter { m == null || it.method.equals(m, true) }
            .filter { status == null || it.responseStatus == status }
            .filter { contains == null || it.url.contains(contains, true) }
            .filter { since == null || !it.timestamp.isBefore(since) }
    }

    private fun historyList(q: Map<String, String>): JSONObject {
        val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
        val pageNo = (q["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val all = filteredHistory(q)
        val arr = JSONArray()
        all.drop((pageNo - 1) * limit).take(limit).forEach { arr.put(summary(it)) }
        return JSONObject().put("transactions", arr).put("total", all.size).put("page", pageNo).put("limit", limit)
            // Only the newest [capacity] transactions are kept (and a memory budget applies); older ones are discarded.
            .put("capacity", store.capacity).put("dropped", store.droppedCount)
    }

    private fun historyOne(id: String): Pair<Int, JSONObject> {
        val tx = store.transactions.value.firstOrNull { it.id == id } ?: return 404 to error("no such transaction")
        return 200 to full(tx)
    }

    private fun interceptState(): JSONObject {
        val arr = JSONArray()
        intercept.pending().forEach { h ->
            arr.put(JSONObject()
                .put("holdId", h.holdId).put("kind", h.kind).put("txId", h.txId)
                .put("method", h.method).put("host", h.host).put("port", h.port)
                .put("target", h.target).put("status", h.status ?: JSONObject.NULL)
                .put("headers", headersJson(h.headers))
                .put("body", h.body?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL))
        }
        return JSONObject().put("enabled", intercept.enabled)
            .put("hostFilter", intercept.hostFilter).put("methodFilter", intercept.methodFilter)
            .put("responses", intercept.interceptResponses).put("skipStatic", intercept.skipStatic)
            .put("pending", arr)
    }

    private fun setIntercept(body: JSONObject): Pair<Int, JSONObject> {
        if (body.has("hostFilter")) intercept.hostFilter = body.optString("hostFilter")
        if (body.has("methodFilter")) intercept.methodFilter = body.optString("methodFilter")
        if (body.has("responses")) intercept.interceptResponses = body.optBoolean("responses")
        if (body.has("skipStatic")) intercept.skipStatic = body.optBoolean("skipStatic")
        if (body.has("enabled")) {
            val on = body.optBoolean("enabled")
            intercept.enabled = on
            if (!on) intercept.releaseAll() // nothing should stay stuck when the breakpoint is switched off
        }
        return 200 to interceptState()
    }

    private fun resolveHold(body: JSONObject): Pair<Int, JSONObject> {
        val holdId = body.optString("holdId"); if (holdId.isBlank()) return 400 to error("holdId required")
        val held = intercept.get(holdId) ?: return 404 to error("no such hold (already resolved?)")
        val action = body.optString("action", "forward")
        val bodyBytes = if (body.has("body")) runCatching { Base64.getDecoder().decode(body.optString("body")) }.getOrNull() else null
        // "headers" replaces the whole list; "setHeaders" only adds/overrides the named ones (the easy way to tweak one).
        val headers = if (body.has("headers")) jsonToHeaders(body.optJSONArray("headers"))
        else jsonToHeaders(body.optJSONArray("setHeaders"))?.let { set ->
            held.headers.filterNot { h -> set.any { it.first.equals(h.first, ignoreCase = true) } } + set
        }
        val ok = if (held.kind == "request") {
            val a = when (action) {
                "drop" -> RequestAction.Drop
                "replace", "edit" -> RequestAction.Replace(
                    method = body.optStringOrNull("method"),
                    target = body.optStringOrNull("target"),
                    headers = headers,
                    body = bodyBytes,
                )
                else -> RequestAction.Forward
            }
            intercept.resolveRequest(holdId, a)
        } else {
            val a = when (action) {
                "drop" -> ResponseAction.Drop
                "replace", "edit" -> ResponseAction.Replace(
                    status = if (body.has("status")) body.optInt("status") else null,
                    headers = headers,
                    body = bodyBytes,
                )
                else -> ResponseAction.Forward
            }
            intercept.resolveResponse(holdId, a)
        }
        return if (ok) 200 to ok() else 409 to error("hold could not be resolved")
    }

    private fun resend(body: JSONObject): Pair<Int, JSONObject> {
        val url = body.optString("url"); if (url.isBlank()) return 400 to error("url required")
        // The repeater speaks HTTP only — never let it be pointed at file:/content:/etc. schemes.
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return 400 to error("resend url must be http(s)")
        val method = body.optString("method", "GET")
        val headers = jsonToHeaders(body.optJSONArray("headers")) ?: emptyList()
        val bodyBytes = if (body.has("body")) runCatching { Base64.getDecoder().decode(body.optString("body")) }.getOrNull() else null
        val r = onResend(ResendRequest(method, url, headers, bodyBytes))
        if (r.error != null) return 502 to error(r.error)
        return 200 to JSONObject().put("status", r.status).put("headers", headersJson(r.headers))
            .put("body", r.body?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
            .put("bodyText", r.body?.let { textOrNull(it) } ?: JSONObject.NULL)
    }

    private fun rulesJson(): JSONObject {
        val arr = JSONArray()
        intercept.rules.all().forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("part", r.part.name).put("find", r.find).put("replace", r.replace)
                .put("hostContains", r.hostContains).put("regex", r.regex).put("enabled", r.enabled))
        }
        return JSONObject().put("rules", arr)
    }

    private fun addRule(body: JSONObject): Pair<Int, JSONObject> {
        val part = runCatching { RuleEngine.Part.valueOf(body.optString("part").uppercase()) }.getOrNull()
            ?: return 400 to error("part must be one of REQ_HEADER, REQ_BODY, RES_HEADER, RES_BODY")
        val id = body.optString("id").ifBlank { "r${System.currentTimeMillis()}" }
        intercept.rules.add(RuleEngine.Rule(
            id = id, part = part, find = body.optString("find"), replace = body.optString("replace"),
            hostContains = body.optString("hostContains"), regex = body.optBoolean("regex"),
            enabled = if (body.has("enabled")) body.optBoolean("enabled") else true,
        ))
        return 200 to JSONObject().put("ok", true).put("id", id)
    }

    private fun consoleJson(limit: Int): JSONObject {
        val arr = JSONArray()
        console.tail(limit).forEach { e ->
            arr.put(JSONObject().put("at", e.at).put("level", e.level).put("message", e.message).put("source", e.source).put("line", e.line))
        }
        return JSONObject().put("console", arr)
    }

    private fun captureJson(): JSONObject = JSONObject().put("capture", captureGet())
        .put("note", if (captureGet()) "traffic goes through the proxy: History has full decrypted requests and responses"
        else "direct browsing: History lists URLs only; turn capture on (or intercept on) for decrypted traffic")

    private fun modeJson(): JSONObject {
        val mode = modeGet()
        val caps = if (mode == "bugbounty")
            "intercept breakpoints, match-and-replace rules, scope, repeater, full request/response history (TLS decrypted)"
        else "console capture, JS eval / DOM, network history with timings, cache/cookie clearing, CDP remote debugging"
        return JSONObject().put("mode", mode).put("capabilities", caps).put("capture", captureGet())
    }

    // --- JSON helpers ---
    private fun summary(tx: HttpTransaction) = JSONObject()
        .put("id", tx.id).put("method", tx.method).put("url", tx.url)
        .put("status", tx.responseStatus ?: JSONObject.NULL).put("opaque", tx.opaque)
        .put("reqLen", tx.requestBody?.size ?: 0).put("resLen", tx.responseBody?.size ?: 0)
        .put("durationMs", tx.durationMs ?: JSONObject.NULL)

    private fun full(tx: HttpTransaction) = summary(tx)
        .put("host", tx.host).put("port", tx.port).put("scheme", tx.scheme).put("path", tx.path)
        .put("requestHeaders", headersJson(tx.requestHeaders))
        .put("requestBody", tx.requestBody?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
        .put("requestBodyText", tx.requestBody?.let { textOrNull(it) } ?: JSONObject.NULL)
        .put("responseHeaders", headersJson(tx.responseHeaders))
        .put("responseBody", tx.responseBody?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
        .put("responseBodyText", tx.responseBody?.let { textOrNull(it) } ?: JSONObject.NULL)
        // Indented JSON / HTML / form fields when the body is one of those (null otherwise): easier to read than the raw text.
        .put("requestBodyPretty", BodyFormat.pretty(tx.requestHeaders, tx.requestBody) ?: JSONObject.NULL)
        .put("responseBodyPretty", BodyFormat.pretty(tx.responseHeaders, tx.responseBody) ?: JSONObject.NULL)

    private fun headersJson(headers: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        for ((n, v) in headers) arr.put(JSONObject().put("name", n).put("value", v))
        return arr
    }

    private fun jsonToHeaders(arr: JSONArray?): List<Pair<String, String>>? {
        if (arr == null) return null
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optString("name") to o.optString("value"))
        }
        return out
    }

    /** UTF-8 text when the bytes are printable, else null (binary stays base64-only). */
    private fun textOrNull(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val sample = bytes.take(2048)
        val binary = sample.count { it.toInt() == 0 || (it.toInt() in 1..8) || it.toInt() in 14..31 && it.toInt() != 27 }
        if (binary > sample.size / 20) return null
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun error(msg: String) = JSONObject().put("error", msg)

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&').mapNotNull {
        val i = it.indexOf('='); if (i < 0) null else
            java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    /**
     * The JSON text with "ok" first when there is one. The CLI tells a failed command from a successful one by the
     * answer starting `{"ok":false`, and JSON objects do not promise any key order (the JVM's does not keep one).
     */
    private fun render(json: JSONObject): String {
        if (!json.has("ok")) return json.toString()
        val rest = JSONObject(json.toString()).also { it.remove("ok") }
        val tail = rest.toString()
        return "{\"ok\":" + json.get("ok") + (if (tail.length > 2) "," + tail.substring(1) else "}")
    }

    private fun respond(out: OutputStream, code: Int, json: JSONObject) {
        val payload = render(json).toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        runCatching { out.write(head.toByteArray(Charsets.US_ASCII)); out.write(payload); out.flush() }
    }

    private val MAX_BODY_BYTES = 16L * 1024 * 1024

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Error"; 502 -> "Bad Gateway"; else -> "Status"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) optString(key) else null
