package com.alpdroid.browser

import com.alpdroid.proxy.HttpTransaction
import com.alpdroid.proxy.TrafficSource
import com.alpdroid.proxy.TransactionStore
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shipped `browserctl` script against the REAL control-API server (not a mock of either side): this is the check
 * that the two halves agree on routes, JSON shapes, raw bodies and exit codes. Set BROWSERCTL_SHELL to run it under
 * another shell (e.g. busybox ash, which is what the Alpine guest has).
 */
class BrowserctlIntegrationTest {
    private val token = "integrationtoken42"
    private lateinit var bridge: BrowserBridge
    private lateinit var work: File
    private val store = TransactionStore()
    private val intercept = InterceptController()
    private var capture = false
    private var openAccess = true
    private var scope: List<String> = emptyList()
    private val pageArgs = ArrayList<JSONObject>()
    private val evaluated = ArrayList<String>()
    private val cookies = ArrayList<CookieSpec>()
    private val openedProfiles = ArrayList<String>()
    private val profileNames = mutableListOf("alice", "bob")
    private val openedTabs = ArrayList<Triple<String, Boolean, Long>>()

    private val script: File = listOf("../app/src/main/assets/browserctl", "app/src/main/assets/browserctl", "../../app/src/main/assets/browserctl")
        .map { File(it) }.first { it.isFile }
    private val shell = System.getenv("BROWSERCTL_SHELL") ?: "/bin/sh"

    private val page = object : PageControl {
        override fun command(args: JSONObject): JSONObject {
            pageArgs += JSONObject(args.toString())
            return if (args.optString("selector") == "#missing")
                JSONObject().put("error", "timeout after 10ms waiting for #missing to be visible").put("ok", false)
            else JSONObject().put("tag", "button").put("cmd", args.optString("cmd")).put("exists", args.optString("selector") != "#none").put("ok", true)
        }
        override fun navigate(url: String, wait: Boolean, state: String, timeoutMs: Long) =
            JSONObject().put("url", "$url/final").put("redirected", true).put("ok", true)
        override fun waitNav(state: String, timeoutMs: Long) = JSONObject().put("navigated", false).put("ok", true)
        override fun eval(js: String, await: Boolean, timeoutMs: Long): JSONObject { evaluated += js; return JSONObject().put("result", js.length).put("ok", true) }
        override fun screenshot(selector: String?, fullPage: Boolean, jpeg: Boolean): Pair<ByteArray?, JSONObject?> =
            if (selector == "#missing") null to JSONObject().put("error", "no element matches #missing").put("ok", false)
            else byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2, 3, 0xff.toByte(), 0xfe.toByte()) to null
        override fun cookies(domain: String?) = JSONObject().put("cookies", JSONArray().put(JSONObject().put("name", "sid").put("value", "1"))).put("ok", true)
        override fun setCookie(cookie: CookieSpec): JSONObject { cookies += cookie; return JSONObject().put("name", cookie.name).put("ok", true) }
    }

    @Before fun setUp() {
        work = java.nio.file.Files.createTempDirectory("bctl").toFile()
        store.record(HttpTransaction("t1", TrafficSource.BROWSER, Instant.parse("2026-10-11T12:00:00Z"), "POST", "https", "api.test", 443, "/login?a=1",
            requestHeaders = listOf("Content-Type" to "application/json"), requestBody = """{"u":"bunny"}""".toByteArray(),
            responseStatus = 200, responseHeaders = listOf("Content-Type" to "application/json"), responseBody = """{"token":"abc"}""".toByteArray(), durationMs = 31))
        store.record(HttpTransaction("t2", TrafficSource.BROWSER, Instant.parse("2026-10-11T12:05:00Z"), "GET", "https", "static.test", 443, "/a.js",
            responseStatus = 404, durationMs = 9))
        bridge = BrowserBridge(
            token = token, store = store, intercept = intercept, proxyPort = { 8890 }, caPem = { "PEM" },
            onNavigate = {}, onEvalJs = { "unused" },
            onResend = { BrowserBridge.ResendResponse(200, emptyList(), "x".toByteArray()) },
            scopeGet = { scope }, scopeSet = { scope = it }, console = ConsoleBuffer(), modeGet = { "bugbounty" }, modeSet = {}, onClear = {},
            preferredPort = 0, captureGet = { capture }, captureSet = { capture = it },
            tabs = object : TabsHost {
                override fun list() = listOf(TabInfo(1, "T", "https://t.test/", true, false, 1L))
                override fun open(url: String, wait: Boolean, timeoutMs: Long): Int? { openedTabs += Triple(url, wait, timeoutMs); return 2 }
                override fun open(url: String, wait: Boolean, timeoutMs: Long, profile: String?): Int? {
                    if (profile == "bad") throw IllegalArgumentException("a profile name is 1-32 letters, digits, - or _")
                    if (profile == "old") throw IllegalStateException("this WebView does not support separate profiles")
                    openedProfiles += profile.orEmpty(); return open(url, wait, timeoutMs)
                }
                override fun profiles() = profileNames.toList()
                override fun deleteProfile(name: String) = profileNames.remove(name)
                override fun switchTo(id: Int) = true
                override fun close(id: Int) = true
            },
            page = page,
            cdp = object : CdpControl {
                override fun status() = JSONObject().put("enabled", true).put("port", 9222).put("ok", true)
                override fun set(enabled: Boolean, preferredPort: Int?) = JSONObject().put("enabled", enabled).put("port", preferredPort ?: 9222)
                    .put("webSocketDebuggerUrl", "ws://127.0.0.1:9222/$token/devtools/browser/abc").put("ok", true)
            },
            appVersion = "t", openAccess = { openAccess },
        )
        bridge.start()
    }

    @After fun tearDown() { bridge.stop(); work.deleteRecursively() }

    private class R(val code: Int, val out: String, val err: String)

    private fun ctl(vararg args: String, tok: String = token, stdin: String? = null): R {
        val pb = ProcessBuilder(listOf(shell, script.absolutePath) + args)
        pb.environment().apply {
            put("ALPBROWSER_TOKEN", tok); put("ALPBROWSER_PORT", bridge.boundPort.toString()); put("BROWSERCTL_ENV", "/dev/null"); put("TMPDIR", work.absolutePath)
            System.getenv("BROWSERCTL_PATH")?.let { put("PATH", it) }
        }
        pb.directory(work)
        val p = pb.start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); error("browserctl timed out: ${args.toList()}") }
        return R(p.exitValue(), out, err)
    }

    @Test fun `ping and a wrong token`() {
        openAccess = false   // with open local access a token is optional, so a wrong one is only refused when it is required
        assertEquals(0, ctl("ping").code)
        assertTrue(ctl("ping", tok = "wrongtoken1").code != 0)
    }

    @Test fun `capture, scope and mode reach the app`() {
        assertEquals(0, ctl("capture", "on").code); assertTrue(capture)
        assertEquals(0, ctl("capture", "off").code); assertFalse(capture)
        assertEquals(0, ctl("scope", "*.api.test", "x.test").code)
        assertEquals(listOf("*.api.test", "x.test"), scope)
    }

    @Test fun `history, get, save and har work end to end`() {
        val lines = ctl("history", "--compact", "--host", "api.test")
        assertEquals(lines.err, 0, lines.code)
        assertEquals("t1 POST 200 31ms api.test/login?a=1", lines.out.trim())

        assertEquals(2, ctl("history", "--compact").out.trim().lines().size)
        assertEquals(1, ctl("history", "--compact", "--limit", "1").out.trim().lines().size)
        assertEquals(1, ctl("history", "--compact", "--limit", "1", "--page", "2").out.trim().lines().size)
        assertEquals(1, JSONObject(ctl("history", "--limit", "1").out).getJSONArray("transactions").length())

        val one = ctl("get", "t1")
        assertEquals(one.err, 0, one.code)
        assertEquals("""{"token":"abc"}""", JSONObject(one.out).getString("responseBodyText"))

        val dir = File(work, "ev")
        assertEquals(0, ctl("save", "t1", "--dir", dir.absolutePath).code)
        assertEquals("POST /login?a=1 HTTP/1.1\nContent-Type: application/json\n\n{\"u\":\"bunny\"}", File(dir, "request.txt").readText())
        assertEquals("""{"token":"abc"}""", File(dir, "response.body").readText())
        assertEquals("t1", JSONObject(File(dir, "meta.json").readText()).getString("id"))

        val har = File(work, "all.har")
        val r = ctl("har", "--host", "api.test", "--out", har.absolutePath)
        assertEquals(r.err, 0, r.code)
        val log = JSONObject(har.readText()).getJSONObject("log")
        assertEquals(1, log.getJSONArray("entries").length())
        assertEquals(2, JSONObject(ctl("har").out).getJSONObject("log").getJSONArray("entries").length())

        val h = JSONObject(ctl("history", "--status", "404").out)
        assertEquals(1, h.getInt("total")); assertEquals(500, h.getInt("capacity"))
    }

    @Test fun `intercept options, rules and tabs`() {
        assertEquals(0, ctl("intercept", "on", "target.test", "--responses").code)
        assertTrue(intercept.enabled && intercept.interceptResponses); assertEquals("target.test", intercept.hostFilter)
        assertEquals(0, ctl("intercept", "off").code); assertFalse(intercept.enabled)
        assertEquals(0, ctl("rule", "header", "req", "X-A: 1", "t.test").code)
        assertEquals("REQ_HEADER", intercept.rules.all().single().part.name)
        assertTrue(ctl("rules").out.contains("X-A: 1"))
        assertEquals(0, ctl("tabs").code)
        assertEquals(0, ctl("tab", "new", "https://n.test/").code)
        assertEquals(0, ctl("tab", "new", "https://m.test/", "--no-wait").code)
        assertEquals(listOf(true, false), openedTabs.map { it.second })
    }

    @Test fun `element commands succeed, fail loudly and handle empty text`() {
        assertEquals(0, ctl("click", "#go").code)
        assertEquals("click", pageArgs.last().getString("cmd"))
        val bad = ctl("click", "#missing")
        assertEquals(1, bad.code)
        assertTrue(bad.err, bad.err.contains("#missing") && bad.err.contains("timeout"))
        assertEquals("", bad.out.trim())
        assertEquals(1, ctl("waitfor", "#missing", "--timeout", "10").code)
        assertEquals(0, ctl("fill", "#f", "").code)
        assertEquals("", pageArgs.last().getString("text"))
        assertEquals(2, ctl("fill", "#f").code)
        assertEquals(0, ctl("exists", "#yes").code)
        assertEquals(1, ctl("exists", "#none").code)
        assertEquals("true", ctl("navigate", "https://x.test", "--wait").out.let { if (it.contains("\"redirected\":true")) "true" else it })
        assertEquals(0, ctl("waitnav").code)
    }

    @Test fun `eval takes a file, stdin and a plain argument, including huge non-ASCII scripts`() {
        val big = File(work, "big.js")
        big.writeText("const a = `v \${1+1} \"q\" \\\\ ب café`;\n".repeat(5000))
        val r = ctl("eval", "--file", big.absolutePath)
        assertEquals(r.err, 0, r.code)
        assertEquals(big.readText().trimEnd('\n'), evaluated.last())
        assertEquals(0, ctl("eval", "--stdin", stdin = "let x = `a\nb`;\nx").code)
        assertEquals("let x = `a\nb`;\nx", evaluated.last())
        assertEquals(0, ctl("eval", "1+1").code); assertEquals("1+1", evaluated.last())
        assertEquals(0, ctl("eval", "--await", "await x").code)
    }

    @Test fun `shot saves the image bytes untouched and reports errors`() {
        val png = File(work, "s.png")
        val r = ctl("shot", "--out", png.absolutePath)
        assertEquals(r.err, 0, r.code)
        assertEquals(listOf<Byte>(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2, 3, 0xff.toByte(), 0xfe.toByte()), png.readBytes().toList())
        assertTrue(r.out.contains("\"bytes\":10"))
        val bad = ctl("shot", "--selector", "#missing", "--out", File(work, "x.png").absolutePath)
        assertEquals(1, bad.code); assertTrue(bad.err.contains("no element matches"))
        assertFalse(File(work, "x.png").exists())
    }

    @Test fun `cdp prints the websocket url and cookies round trip`() {
        assertEquals("ws://127.0.0.1:9222/$token/devtools/browser/abc", ctl("cdp").out.trim())
        assertEquals("0", ctl("cdp", "off").out.trim())
        assertTrue(ctl("cookies", "--domain", "api.test").out.contains("sid"))
        assertEquals(0, ctl("setcookie", "sid", "9", "--domain", "api.test").code)
        assertEquals(0, ctl("setcookie", "tok", "v1", "--domain", "api.test", "--path", "/app", "--secure", "--httponly", "--samesite", "Strict", "--max-age", "60", "--expires", "1900000000").code)
        val c = cookies.last()
        assertEquals(listOf("tok", "v1", "api.test", "/app"), listOf(c.name, c.value, c.domain, c.path))
        assertTrue(c.secure && c.httpOnly); assertEquals("Strict", c.sameSite); assertEquals(60L, c.maxAge); assertEquals(1900000000L, c.expires)
    }

    private fun rawStatus(vararg headers: String): Int {
        java.net.Socket("127.0.0.1", bridge.boundPort).use { sock ->
            sock.soTimeout = 5000
            sock.getOutputStream().write(("GET /v1/ping HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray())
            return sock.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }
    }

    @Test fun `open local access needs no token for programs but refuses web pages`() {
        assertEquals(0, ctl("ping", tok = "").code)
        assertEquals(200, rawStatus("Host: 127.0.0.1:${bridge.boundPort}"))
        assertEquals(401, rawStatus("Host: 127.0.0.1:${bridge.boundPort}", "Origin: https://evil.test"))
        assertEquals(401, rawStatus("Host: 127.0.0.1:${bridge.boundPort}", "Sec-Fetch-Site: cross-site"))
        assertEquals(401, rawStatus("Host: rebind.evil.test:${bridge.boundPort}"))
        assertEquals(401, rawStatus("Host: evil.test"))
        assertEquals(200, rawStatus("Host: localhost:${bridge.boundPort}"))
    }

    @Test fun `with open local access off the token is required again`() {
        openAccess = false
        assertTrue(ctl("ping", tok = "").code != 0)
        assertEquals(401, rawStatus("Host: 127.0.0.1:${bridge.boundPort}"))
        assertEquals(0, ctl("ping").code)
    }

    @Test fun `frames lists the messages of a websocket and the events of a stream`() {
        store.record(HttpTransaction("w1", TrafficSource.BROWSER, Instant.parse("2026-10-11T13:00:00Z"), "GET", "wss", "ws.test", 443, "/live", responseStatus = 101, durationMs = 5))
        val ws = store.webSockets.open("w1", "websocket")
        ws.add(false, 1, true, 7, "welcome".toByteArray(), false)
        ws.add(true, 1, true, 4, "ping\nx".toByteArray(), false)
        ws.add(false, 2, true, 3, byteArrayOf(1, 2, 3), false)

        val json = JSONObject(ctl("frames", "w1").out)
        assertEquals("websocket", json.getString("kind")); assertEquals(3, json.getInt("total"))
        val f = json.getJSONArray("frames")
        assertEquals(listOf("server", "client", "server"), (0 until 3).map { f.getJSONObject(it).getString("dir") })
        assertEquals("welcome", f.getJSONObject(0).getString("text"))
        assertTrue(f.getJSONObject(2).isNull("text") && f.getJSONObject(2).getString("base64") == "AQID")

        val lines = ctl("frames", "w1", "--compact").out.trim().lines()
        assertEquals(listOf("0 <- text 7B welcome", "1 -> text 4B ping\\nx", "2 <- binary 3B <3B>"), lines)
        assertEquals(1, ctl("frames", "w1", "--from", "2", "--compact").out.trim().lines().size)
        assertEquals(1, JSONObject(ctl("frames", "w1", "--limit", "1").out).getJSONArray("frames").length())

        assertTrue(ctl("history", "--host", "ws.test", "--compact").out.contains("[websocket 3]"))
        assertEquals("websocket", JSONObject(ctl("history", "--host", "ws.test").out).getJSONArray("transactions").getJSONObject(0).getString("kind"))
        assertTrue(ctl("frames", "t1").code != 0)   // an ordinary request has none
    }

    @Test fun `tab new can open a tab in its own profile, and profiles can be listed and deleted`() {
        assertEquals(0, ctl("tab", "new", "https://a.test/", "--profile", "alice", "--no-wait").code)
        assertEquals(listOf("alice"), openedProfiles)
        val bad = ctl("tab", "new", "https://a.test/", "--profile", "bad")
        assertEquals(1, bad.code); assertTrue(bad.err, bad.err.contains("1-32 letters"))
        val old = ctl("tab", "new", "https://a.test/", "--profile", "old")
        assertEquals(1, old.code); assertTrue(old.err.contains("does not support separate profiles"))
        assertTrue(ctl("tab", "new", "x", "--profile").code != 0)                       // a missing name is refused by the CLI
        assertEquals(listOf("alice", "bob"), JSONObject(ctl("profile", "list").out).getJSONArray("profiles").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(0, ctl("profile", "delete", "bob").code)
        assertEquals(listOf("alice"), profileNames)
        val gone = ctl("profile", "delete", "bob")
        assertEquals(1, gone.code); assertTrue(gone.err.contains("could not delete"))
        assertTrue(JSONObject(ctl("tabs").out).getJSONArray("tabs").getJSONObject(0).has("profile"))
    }
}
