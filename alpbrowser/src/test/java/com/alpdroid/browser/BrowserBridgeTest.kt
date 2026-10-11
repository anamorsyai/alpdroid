package com.alpdroid.browser

import com.alpdroid.proxy.HttpTransaction
import com.alpdroid.proxy.TrafficSource
import com.alpdroid.proxy.TransactionStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserBridgeTest {
    private lateinit var bridge: BrowserBridge
    private lateinit var store: TransactionStore
    private val navigated = ArrayList<String>()
    private val cleared = ArrayList<String>()
    private var scope: List<String> = emptyList()
    private var mode = "bugbounty"
    private val console = ConsoleBuffer()
    private val token = "secret-token"
    private var capture = false
    private val pageCalls = ArrayList<String>()
    private val fakePage = object : PageControl {
        override fun command(args: JSONObject): JSONObject { pageCalls += "act:" + args.toString(); return JSONObject().put("ok", true).put("cmd", args.optString("cmd")) }
        override fun navigate(url: String, wait: Boolean, state: String, timeoutMs: Long): JSONObject { pageCalls += "nav:$url:$wait:$state:$timeoutMs"; return JSONObject().put("ok", true).put("url", url) }
        override fun waitNav(state: String, timeoutMs: Long): JSONObject { pageCalls += "waitnav:$state:$timeoutMs"; return JSONObject().put("ok", true) }
        override fun eval(js: String, await: Boolean, timeoutMs: Long): JSONObject { pageCalls += "eval:$await:$timeoutMs:${js.length}"; return JSONObject().put("ok", true).put("result", js.length) }
        override fun screenshot(selector: String?, fullPage: Boolean, jpeg: Boolean): Pair<ByteArray?, JSONObject?> {
            pageCalls += "shot:$selector:$fullPage:$jpeg"
            return if (selector == "#missing") null to JSONObject().put("ok", false).put("error", "no element matches #missing") else byteArrayOf(1, 2, 3) to null
        }
        override fun cookies(domain: String?) = JSONObject().put("ok", true).put("cookies", org.json.JSONArray()).put("domain", domain ?: JSONObject.NULL)
        override fun setCookie(name: String, value: String, domain: String?, path: String?): JSONObject { pageCalls += "cookie:$name=$value:$domain:$path"; return JSONObject().put("ok", true) }
    }
    private val fakeCdp = object : CdpControl {
        override fun status() = JSONObject().put("ok", true).put("enabled", true).put("port", 9222)
        override fun set(enabled: Boolean, preferredPort: Int?) = JSONObject().put("ok", true).put("enabled", enabled).put("port", preferredPort ?: 9222)
    }
    private val openedWait = ArrayList<Boolean>()
    private val fakeTabs = mutableListOf(TabInfo(1, "One", "https://a.test/", true))
    private val tabsHost = object : TabsHost {
        override fun list() = fakeTabs.toList()
        override fun open(url: String, wait: Boolean, timeoutMs: Long): Int? {
            openedWait += wait
            return if (fakeTabs.size >= 3) null else (fakeTabs.maxOf { it.id } + 1).also { fakeTabs.add(TabInfo(it, "", url, false)) }
        }
        override fun switchTo(id: Int) = fakeTabs.any { it.id == id }
        override fun close(id: Int) = fakeTabs.removeAll { it.id == id }
    }

    @Before fun setUp() {
        store = TransactionStore()
        store.record(
            HttpTransaction(
                id = "abc", source = TrafficSource.BROWSER, timestamp = Instant.parse("2026-10-11T10:00:00Z"), method = "GET",
                scheme = "https", host = "example.com", port = 443, path = "/x",
                responseStatus = 200, responseBody = "hello".toByteArray(),
            ),
        )
        store.record(
            HttpTransaction(
                id = "def", source = TrafficSource.BROWSER, timestamp = Instant.parse("2026-10-11T12:00:00Z"), method = "POST",
                scheme = "https", host = "api.test", port = 443, path = "/v1/login?x=1&y=%20z",
                requestHeaders = listOf("Content-Type" to "application/json"), requestBody = """{"user":"bunny"}""".toByteArray(),
                responseStatus = 201, responseHeaders = listOf("Content-Type" to "application/json"), responseBody = """{"ok":true}""".toByteArray(),
                durationMs = 42,
            ),
        )
        bridge = BrowserBridge(
            token = token, store = store, intercept = InterceptController(),
            proxyPort = { 8890 }, caPem = { "PEMDATA" },
            onNavigate = { navigated.add(it) },
            onEvalJs = { "\"evaluated:$it\"" },
            onResend = { BrowserBridge.ResendResponse(200, listOf("X" to "1"), "replayed".toByteArray()) },
            scopeGet = { scope },
            scopeSet = { scope = it },
            console = console,
            modeGet = { mode },
            modeSet = { mode = it },
            onClear = { cleared.add(it) },
            preferredPort = 0,
            captureGet = { capture }, captureSet = { capture = it }, tabs = tabsHost, page = fakePage, cdp = fakeCdp, appVersion = "9.9",
        )
        bridge.start()
    }

    @After fun tearDown() = bridge.stop()

    private fun call(method: String, path: String, withToken: Boolean = true, body: String? = null): Pair<Int, JSONObject> {
        Socket("127.0.0.1", bridge.boundPort).use { s ->
            s.soTimeout = 5000
            val out = s.getOutputStream()
            val payload = body?.toByteArray()
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: x\r\n")
            if (withToken) sb.append("Authorization: Bearer $token\r\n")
            if (payload != null) sb.append("Content-Length: ${payload.size}\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
            if (payload != null) out.write(payload)
            out.flush()
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val statusLine = reader.readLine()
            val code = statusLine.split(" ")[1].toInt()
            var len = 0
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(":").trim().toInt() }
            val buf = CharArray(len); var r = 0; while (r < len) { val n = reader.read(buf, r, len - r); if (n < 0) break; r += n }
            return code to JSONObject(String(buf, 0, r))
        }
    }

    @Test fun `ping needs the token`() {
        assertEquals(401, call("GET", "/v1/ping", withToken = false).first)
        val (code, json) = call("GET", "/v1/ping")
        assertEquals(200, code)
        assertEquals("AlpBrowser", json.getString("app"))
        assertEquals(8890, json.getInt("proxyPort"))
    }

    @Test fun `history lists and reads one transaction with decoded body`() {
        val (_, list) = call("GET", "/v1/history")
        assertEquals(2, list.getJSONArray("transactions").length())
        val (code, full) = call("GET", "/v1/history/abc")
        assertEquals(200, code)
        assertEquals("hello", full.getString("responseBodyText"))
        assertEquals(404, call("GET", "/v1/history/missing").first)
    }

    @Test fun `navigate and eval reach their callbacks`() {
        assertEquals(200, call("POST", "/v1/navigate", body = JSONObject().put("url", "https://t.test").toString()).first)
        assertEquals("https://t.test", navigated.single())
        val (_, ev) = call("POST", "/v1/eval", body = JSONObject().put("js", "1+1").toString())
        assertEquals("3", ev.getString("result"))
    }

    @Test fun `intercept state can be toggled`() {
        val (_, before) = call("GET", "/v1/intercept")
        assertEquals(false, before.getBoolean("enabled"))
        call("POST", "/v1/intercept", body = JSONObject().put("enabled", true).put("hostFilter", "t.test").toString())
        val (_, after) = call("GET", "/v1/intercept")
        assertEquals(true, after.getBoolean("enabled"))
        assertEquals("t.test", after.getString("hostFilter"))
    }

    @Test fun `resend returns the replayed response`() {
        val (code, json) = call("POST", "/v1/resend", body = JSONObject().put("url", "https://t.test").put("method", "GET").toString())
        assertEquals(200, code)
        assertEquals(200, json.getInt("status"))
        assertEquals("replayed", json.getString("bodyText"))
    }

    @Test fun `ca is returned`() {
        assertEquals("PEMDATA", call("GET", "/v1/ca").second.getString("pem"))
    }

    @Test fun `a wrong token is rejected`() {
        Socket("127.0.0.1", bridge.boundPort).use { s ->
            s.getOutputStream().write("GET /v1/ping HTTP/1.1\r\nAuthorization: Bearer wrong\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val code = BufferedReader(InputStreamReader(s.getInputStream())).readLine().split(" ")[1].toInt()
            assertEquals(401, code)
        }
    }

    @Test fun `scope can be set and read`() {
        call("POST", "/v1/scope", body = org.json.JSONObject().put("patterns", org.json.JSONArray(listOf("*.target.com"))).toString())
        assertEquals("*.target.com", scope.single())
        assertEquals("*.target.com", call("GET", "/v1/scope").second.getJSONArray("patterns").getString(0))
    }

    @Test fun `a match-replace rule can be added listed and deleted`() {
        val (code, add) = call("POST", "/v1/rules", body = org.json.JSONObject()
            .put("part", "req_header").put("find", "").put("replace", "X-Test: 1").put("id", "r1").toString())
        assertEquals(200, code)
        assertEquals(1, call("GET", "/v1/rules").second.getJSONArray("rules").length())
        assertEquals(200, call("POST", "/v1/rules/delete", body = org.json.JSONObject().put("id", "r1").toString()).first)
        assertEquals(0, call("GET", "/v1/rules").second.getJSONArray("rules").length())
    }

    @Test fun `mode switches and reports capabilities`() {
        call("POST", "/v1/mode", body = org.json.JSONObject().put("mode", "webdev").toString())
        assertEquals("webdev", mode)
        assertTrue(call("GET", "/v1/mode").second.getString("capabilities").contains("console"))
        assertEquals(400, call("POST", "/v1/mode", body = org.json.JSONObject().put("mode", "bogus").toString()).first)
    }

    @Test fun `console entries are returned`() {
        console.add("ERROR", "boom", "app.js", 42)
        val arr = call("GET", "/v1/console").second.getJSONArray("console")
        assertEquals("boom", arr.getJSONObject(0).getString("message"))
    }

    @Test fun `clear reaches its callback`() {
        call("POST", "/v1/clear", body = org.json.JSONObject().put("what", "cookies").toString())
        assertEquals("cookies", cleared.single())
    }

    @Test fun `capture can be read and switched`() {
        assertEquals(false, call("GET", "/v1/capture").second.getBoolean("capture"))
        assertEquals(true, call("POST", "/v1/capture", body = """{"enabled":true}""").second.getBoolean("capture"))
        assertEquals(400, call("POST", "/v1/capture", body = "{}").first)
    }

    @Test fun `tabs can be listed opened switched and closed`() {
        assertEquals(1, call("GET", "/v1/tabs").second.getJSONArray("tabs").length())
        val (code, opened) = call("POST", "/v1/tabs", body = """{"action":"new","url":"https://b.test"}""")
        assertEquals(200, code)
        assertEquals(2, opened.getInt("id"))
        assertEquals(200, call("POST", "/v1/tabs", body = """{"action":"switch","id":2}""").first)
        assertEquals(404, call("POST", "/v1/tabs", body = """{"action":"switch","id":99}""").first)
        assertEquals(200, call("POST", "/v1/tabs", body = """{"action":"close","id":2}""").first)
        assertEquals(400, call("POST", "/v1/tabs", body = """{"action":"nope"}""").first)
        call("POST", "/v1/tabs", body = """{"action":"new"}"""); call("POST", "/v1/tabs", body = """{"action":"new"}""")
        assertEquals(409, call("POST", "/v1/tabs", body = """{"action":"new"}""").first)
    }

    @Test fun `intercept settings include the response and asset switches`() {
        val j = call("POST", "/v1/intercept", body = """{"enabled":true,"responses":true,"skipStatic":false}""").second
        assertEquals(true, j.getBoolean("responses"))
        assertEquals(false, j.getBoolean("skipStatic"))
    }

    @Test fun `a rule can be switched off through the api`() {
        val id = call("POST", "/v1/rules", body = """{"part":"RES_BODY","find":"a","replace":"b"}""").second.getString("id")
        assertEquals(200, call("POST", "/v1/rules/enable", body = """{"id":"$id","enabled":false}""").first)
        assertEquals(404, call("POST", "/v1/rules/enable", body = """{"id":"nope"}""").first)
    }

    private fun callRaw(path: String, withToken: Boolean = true, method: String = "GET", body: ByteArray? = null): Triple<Int, String, ByteArray> {
        Socket("127.0.0.1", bridge.boundPort).use { sock ->
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: x\r\n")
            if (withToken) sb.append("Authorization: Bearer $token\r\n")
            if (body != null) sb.append("Content-Length: ${body.size}\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray()); if (body != null) out.write(body); out.flush()
            val all = sock.getInputStream().readBytes()
            val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val head = String(all, 0, split, Charsets.ISO_8859_1)
            val type = Regex("Content-Type: ([^\r]*)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1) ?: ""
            return Triple(head.split(" ")[1].toInt(), type, all.copyOfRange(split + 4, all.size))
        }
    }

    @Test fun `a script with non-ASCII text arrives intact because the body is read as bytes`() {
        val js = "const s = `مرحبا ${'$'}{1+1} café`; // " + "ب".repeat(20_000)
        val payload = JSONObject().put("js", js).toString().toByteArray(Charsets.UTF_8)
        assertTrue("more bytes than characters, the case that used to hang", payload.size > payload.toString(Charsets.UTF_8).length)
        val (code, _, bytes) = callRaw("/v1/eval", method = "POST", body = payload)
        assertEquals(200, code)
        assertEquals(js.length.toString(), JSONObject(String(bytes, Charsets.UTF_8)).getString("result"))
    }

    @Test fun `invalid json is rejected with a reason instead of being treated as empty`() {
        val (code, _, bytes) = callRaw("/v1/eval", method = "POST", body = "{not json".toByteArray())
        assertEquals(400, code)
        assertTrue(String(bytes).contains("not valid JSON"))
    }

    @Test fun `element commands, waits and cookies reach the page controller`() {
        assertEquals(200, call("POST", "/v1/act", body = """{"cmd":"click","selector":"#go","timeout":500}""").first)
        assertEquals(400, call("POST", "/v1/act", body = """{"selector":"#go"}""").first)
        assertEquals(200, call("POST", "/v1/navigate", body = """{"url":"https://a.test/","wait":true,"state":"networkidle","timeout":9000}""").first)
        assertEquals(0, navigated.size) // a waiting navigation is the page controller's job, not the queued command
        assertEquals(200, call("POST", "/v1/navigate", body = """{"url":"https://b.test/"}""").first)
        assertEquals(listOf("https://b.test/"), navigated)
        assertEquals(200, call("POST", "/v1/waitnav", body = """{"state":"load","timeout":4000}""").first)
        assertEquals(200, call("POST", "/v1/cookies", body = """{"name":"sid","value":"1","domain":"a.test"}""").first)
        assertEquals(200, call("GET", "/v1/cookies?domain=a.test").first)
        assertTrue(pageCalls.toString(), pageCalls.any { it.startsWith("act:") && it.contains("\"click\"") })
        assertTrue(pageCalls.contains("nav:https://a.test/:true:networkidle:9000"))
        assertTrue(pageCalls.contains("waitnav:load:4000"))
        assertTrue(pageCalls.contains("cookie:sid=1:a.test:null"))
    }

    @Test fun `eval with await goes through the page controller`() {
        call("POST", "/v1/eval", body = """{"js":"await x","await":true,"timeout":1234}""")
        assertTrue(pageCalls.toString(), pageCalls.contains("eval:true:1234:7"))
    }

    @Test fun `screenshots come back as image bytes, errors as json`() {
        val (code, type, bytes) = callRaw("/v1/shot?fullPage=1&format=jpeg")
        assertEquals(200, code); assertEquals("image/jpeg", type); assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
        assertTrue(pageCalls.contains("shot:null:true:true"))
        val (_, errType, errBytes) = callRaw("/v1/shot?selector=%23missing")
        assertEquals("application/json", errType)
        assertTrue(String(errBytes).startsWith("{\"ok\":false") || String(errBytes).contains("\"ok\":false"))
        assertEquals(401, callRaw("/v1/shot", withToken = false).first)
    }

    @Test fun `a transaction can be saved as raw request, raw response, meta and body`() {
        val (_, _, req) = callRaw("/v1/history/def/request")
        assertEquals("POST /v1/login?x=1&y=%20z HTTP/1.1\nContent-Type: application/json\n\n{\"user\":\"bunny\"}", String(req))
        val (_, _, res) = callRaw("/v1/history/def/response")
        assertEquals("HTTP/1.1 201\nContent-Type: application/json\n\n{\"ok\":true}", String(res))
        val meta = JSONObject(String(callRaw("/v1/history/def/meta").third))
        assertEquals(201, meta.getInt("status")); assertEquals("api.test", meta.getString("host"))
        assertEquals("""{"ok":true}""", String(callRaw("/v1/history/def/body/response").third))
        assertEquals("""{"user":"bunny"}""", String(callRaw("/v1/history/def/body/request").third))
        assertEquals(404, callRaw("/v1/history/nope/request").first)
    }

    @Test fun `history can be filtered, paged, summarised and its retention is reported`() {
        val j = call("GET", "/v1/history?method=POST&limit=1&page=1").second
        assertEquals(1, j.getJSONArray("transactions").length()); assertEquals(1, j.getInt("total"))
        assertEquals(500, j.getInt("capacity")); assertEquals(0, j.getLong("dropped"))
        assertEquals(1, call("GET", "/v1/history?since=2026-10-11T11:00:00Z").second.getInt("total"))
        assertEquals(2, call("GET", "/v1/history?since=2026-10-11T09:00:00Z").second.getInt("total"))
        val lines = String(callRaw("/v1/history?fmt=lines&status=201").third)
        assertEquals("def POST 201 42ms api.test/v1/login?x=1&y=%20z\n", lines)
        assertEquals(0, call("GET", "/v1/history?page=9").second.getJSONArray("transactions").length())
    }

    @Test fun `har export contains decoded entries`() {
        val har = JSONObject(String(callRaw("/v1/har?host=api.test").third)).getJSONObject("log")
        assertEquals("1.2", har.getString("version")); assertEquals("9.9", har.getJSONObject("creator").getString("version"))
        val e = har.getJSONArray("entries").getJSONObject(0)
        assertEquals("POST", e.getJSONObject("request").getString("method"))
        assertEquals("""{"user":"bunny"}""", e.getJSONObject("request").getJSONObject("postData").getString("text"))
        assertEquals("""{"ok":true}""", e.getJSONObject("response").getJSONObject("content").getString("text"))
        assertEquals("y", e.getJSONObject("request").getJSONArray("queryString").getJSONObject(1).getString("name"))
        assertEquals(" z", e.getJSONObject("request").getJSONArray("queryString").getJSONObject(1).getString("value"))
    }

    @Test fun `cdp can be turned on and asked for its status`() {
        assertEquals(true, call("GET", "/v1/cdp").second.getBoolean("enabled"))
        val j = call("POST", "/v1/cdp", body = """{"enabled":true,"port":9333}""").second
        assertEquals(9333, j.getInt("port"))
    }

    @Test fun `tabs report load state and wait by default when opened`() {
        val t = call("GET", "/v1/tabs").second.getJSONArray("tabs").getJSONObject(0)
        assertTrue(t.has("loading") && t.has("lastActiveAt"))
        call("POST", "/v1/tabs", body = """{"action":"new","url":"https://x.test"}""")
        assertEquals(listOf(true), openedWait)
        call("POST", "/v1/tabs", body = """{"action":"new","url":"https://y.test","wait":false}""")
        assertEquals(listOf(true, false), openedWait)
    }
}
