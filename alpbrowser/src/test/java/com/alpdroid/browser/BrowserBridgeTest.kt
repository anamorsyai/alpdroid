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

    @Before fun setUp() {
        store = TransactionStore()
        store.record(
            HttpTransaction(
                id = "abc", source = TrafficSource.BROWSER, timestamp = Instant.now(), method = "GET",
                scheme = "https", host = "example.com", port = 443, path = "/x",
                responseStatus = 200, responseBody = "hello".toByteArray(),
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
        assertEquals(1, list.getJSONArray("transactions").length())
        val (code, full) = call("GET", "/v1/history/abc")
        assertEquals(200, code)
        assertEquals("hello", full.getString("responseBodyText"))
        assertEquals(404, call("GET", "/v1/history/missing").first)
    }

    @Test fun `navigate and eval reach their callbacks`() {
        assertEquals(200, call("POST", "/v1/navigate", body = JSONObject().put("url", "https://t.test").toString()).first)
        assertEquals("https://t.test", navigated.single())
        val (_, ev) = call("POST", "/v1/eval", body = JSONObject().put("js", "1+1").toString())
        assertTrue(ev.getString("result").contains("evaluated:1+1"))
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
}
