package com.alpdroid.browser

import com.alpdroid.proxy.HttpTransaction
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import com.alpdroid.proxy.TransactionStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
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
) {
    data class ResendRequest(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray?)
    data class ResendResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray?, val error: String? = null)

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "browser-bridge").apply { isDaemon = true } }

    val boundPort: Int get() = server?.localPort ?: -1

    /** Binds loopback-only. Tries [preferredPort] first, then an ephemeral port, so two installs never collide fatally. */
    @Synchronized
    fun start() {
        if (server != null) return
        val loopback = InetAddress.getByName("127.0.0.1")
        val s = runCatching { ServerSocket(preferredPort, 64, loopback) }.getOrElse { ServerSocket(0, 64, loopback) }
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { runCatching { handle(c) } }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(sock: Socket) {
        sock.use {
            sock.soTimeout = 15_000
            val input = sock.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size != 3 || (parts[0] != "GET" && parts[0] != "POST")) { respond(sock.getOutputStream(), 400, error("bad request")); return }
            val method = parts[0]
            var authHeader: String? = null
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (name == "authorization") authHeader = value
                if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
            }
            val rawPath = parts[1]
            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val query = if (qIdx >= 0) parseQuery(rawPath.substring(qIdx + 1)) else emptyMap()

            // Token: Bearer header or ?token=. Constant-time-ish compare.
            val presented = authHeader?.removePrefix("Bearer ")?.trim() ?: query["token"] ?: ""
            if (!tokenOk(presented)) { respond(sock.getOutputStream(), 401, error("unauthorized")); return }

            val bodyText = if (contentLength in 1..(8 * 1024 * 1024)) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) { val n = reader.read(buf, read, contentLength - read); if (n < 0) break; read += n }
                String(buf, 0, read)
            } else ""
            val body = runCatching { if (bodyText.isBlank()) JSONObject() else JSONObject(bodyText) }.getOrDefault(JSONObject())

            val (code, json) = try { route(method, path, query, body) } catch (e: Exception) { 500 to error(e.message ?: "error") }
            respond(sock.getOutputStream(), code, json)
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
            path == "/v1/ping" -> return 200 to JSONObject().put("ok", true).put("app", "AlpBrowser").put("proxyPort", proxyPort())
            path == "/v1/ca" && method == "GET" -> return 200 to JSONObject().put("pem", caPem() ?: "")
            path == "/v1/history" && method == "GET" -> return 200 to historyList(q)
            path.startsWith("/v1/history/") && method == "GET" -> return historyOne(path.removePrefix("/v1/history/"))
            path == "/v1/intercept" && method == "GET" -> return 200 to interceptState()
            path == "/v1/intercept" && method == "POST" -> return setIntercept(body)
            path == "/v1/intercept/resolve" && method == "POST" -> return resolveHold(body)
            path == "/v1/navigate" && method == "POST" -> {
                val url = body.optString("url"); if (url.isBlank()) return 400 to error("url required")
                onNavigate(url); return 200 to ok()
            }
            path == "/v1/eval" && method == "POST" -> {
                val js = body.optString("js"); if (js.isBlank()) return 400 to error("js required")
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
            path == "/v1/console" && method == "GET" -> return 200 to consoleJson(q["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200)
            path == "/v1/console/clear" && method == "POST" -> { console.clear(); return 200 to ok() }
            path == "/v1/mode" && method == "GET" -> return 200 to modeJson()
            path == "/v1/mode" && method == "POST" -> {
                val m = body.optString("mode"); if (m != "bugbounty" && m != "webdev") return 400 to error("mode must be bugbounty or webdev")
                modeSet(m); return 200 to modeJson()
            }
            path == "/v1/clear" && method == "POST" -> { onClear(body.optString("what", "all")); return 200 to ok() }
            else -> return 404 to error("unknown endpoint")
        }
    }

    private fun historyList(q: Map<String, String>): JSONObject {
        val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
        val host = q["host"]; val m = q["method"]; val status = q["status"]?.toIntOrNull(); val contains = q["contains"]
        val arr = JSONArray()
        store.transactions.value.asReversed().asSequence()
            .filter { host == null || it.host.contains(host, true) }
            .filter { m == null || it.method.equals(m, true) }
            .filter { status == null || it.responseStatus == status }
            .filter { contains == null || it.url.contains(contains, true) }
            .take(limit)
            .forEach { arr.put(summary(it)) }
        return JSONObject().put("transactions", arr)
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
            .put("pending", arr)
    }

    private fun setIntercept(body: JSONObject): Pair<Int, JSONObject> {
        if (body.has("hostFilter")) intercept.hostFilter = body.optString("hostFilter")
        if (body.has("methodFilter")) intercept.methodFilter = body.optString("methodFilter")
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
        val headers = if (body.has("headers")) jsonToHeaders(body.optJSONArray("headers")) else null
        val ok = if (held.kind == "request") {
            val a = when (action) {
                "drop" -> RequestAction.Drop
                "replace" -> RequestAction.Replace(
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
                "replace" -> ResponseAction.Replace(
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

    private fun modeJson(): JSONObject {
        val mode = modeGet()
        val caps = if (mode == "bugbounty")
            "intercept breakpoints, match-and-replace rules, scope, repeater, full request/response history (TLS decrypted)"
        else "console capture, JS eval / DOM, network history with timings, cache/cookie clearing, CDP remote debugging"
        return JSONObject().put("mode", mode).put("capabilities", caps)
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

    private fun respond(out: OutputStream, code: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        runCatching { out.write(head.toByteArray(Charsets.US_ASCII)); out.write(payload); out.flush() }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Error"; 502 -> "Bad Gateway"; else -> "Status"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) optString(key) else null
