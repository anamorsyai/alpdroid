package com.alpdroid.app

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the REAL `browserctl` script (the asset AlpDroid installs into Alpine) against a local HTTP server that
 * mimics AlpBrowser's control API — including Android's org.json habit of escaping every "/" as "\/", which once
 * silently corrupted the CA certificate. Skipped where `sh`/`wget`/`sed`/`tr` are missing.
 */
class BrowserctlScriptTest {
    private lateinit var server: ServerSocket
    private lateinit var work: File
    private val posts = CopyOnWriteArrayList<Pair<String, String>>()
    private val gets = CopyOnWriteArrayList<String>()
    private var caMode = "ok"

    private val pem = "-----BEGIN CERTIFICATE-----\n" +
        "MIIDazCCAlOgAwIBAgIUa/bc+d/ef//gh+ij/klmnopqrstuv0123456789\n" +
        "AbCdEfGhIjKlMnOpQrStUvWxYz+/AbCdEfGhIjKlMnOpQrStUvWxYz+/Ab==\n" +
        "-----END CERTIFICATE-----\n"

    /** org.json (Android) escapes '/' as '\/' and writes no spaces — reproduce that exactly. */
    private fun orgJson(o: JSONObject) = o.toString().replace("/", "\\/")

    private fun script(): File = listOf("src/main/assets/browserctl", "app/src/main/assets/browserctl")
        .map { File(it) }.first { it.isFile }

    @Before fun setUp() {
        assumeTrue("needs a POSIX sh", File("/bin/sh").exists())
        for (tool in listOf("wget", "sed", "tr", "grep")) {
            assumeTrue("needs $tool", listOf("/usr/bin", "/bin", "/usr/local/bin").any { File(it, tool).exists() })
        }
        work = File.createTempFile("browserctl", "").let { it.delete(); it.mkdirs(); it }
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        Thread({
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: Exception) { break }
                Thread { runCatching { c.use { serve(it) } } }.apply { isDaemon = true }.start()
            }
        }, "fake-alpbrowser").apply { isDaemon = true }.start()
    }

    @After fun tearDown() {
        runCatching { server.close() } // a test may already have stopped it
        work.deleteRecursively()
    }

    private val port: Int get() = server.localPort

    /** One HTTP/1.0-style exchange: just enough of the protocol for wget. */
    private fun serve(s: Socket) {
        val input = s.getInputStream()
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
        val request = line() ?: return
        val (method, target) = request.split(" ").let { it[0] to it[1] }
        var auth = ""
        var length = 0
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Authorization:", ignoreCase = true)) auth = h.substringAfter(':').trim()
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toInt()
        }
        val body = if (length > 0) ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }.toString(Charsets.UTF_8) else ""
        val path = target.substringBefore('?')

        fun raw(code: Int, type: String, bytes: ByteArray) {
            s.getOutputStream().apply {
                write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }
        fun reply(code: Int, text: String, o: JSONObject) {
            val b = orgJson(o).toByteArray()
            s.getOutputStream().apply {
                write("HTTP/1.1 $code $text\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(b); flush()
            }
        }
        if (auth != "Bearer tok123") return reply(401, "Unauthorized", JSONObject().put("error", "unauthorized"))
        if (method == "POST") {
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            if (parsed == null) { posts += path to "INVALID:$body"; return reply(400, "Bad Request", JSONObject().put("error", "bad json")) }
            posts += path to body
            // The real API answers 200 with {"ok":false,...} (ok first) when an element command fails.
            if (parsed.optString("selector") == "#missing") return raw(200, "application/json", "{\"ok\":false,\"error\":\"timeout after 10ms waiting for #missing to be visible\"}".toByteArray())
            if (path == "/v1/cdp") return raw(200, "application/json", "{\"ok\":true,\"enabled\":true,\"port\":9222,\"webSocketDebuggerUrl\":\"ws:\\/\\/127.0.0.1:9222\\/tok\\/devtools\\/browser\\/abc\"}".toByteArray())
            return reply(200, "OK", JSONObject().put("ok", true).put("echo", parsed))
        }
        gets += target
        if (path == "/v1/shot") {
            return if (java.net.URLDecoder.decode(target, "UTF-8").contains("selector=#missing")) raw(200, "application/json", "{\"ok\":false,\"error\":\"no element matches #missing\"}".toByteArray())
            else raw(200, "image/png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3, 4))
        }
        if (path.startsWith("/v1/history/abc/")) {
            return when (path.removePrefix("/v1/history/abc/")) {
                "request" -> raw(200, "text/plain", "GET /x HTTP/1.1\nHost: t\n\n".toByteArray())
                "response" -> raw(200, "text/plain", "HTTP/1.1 200\n\nhello".toByteArray())
                "meta" -> raw(200, "application/json", "{\"id\":\"abc\"}".toByteArray())
                "body/response" -> raw(200, "application/octet-stream", "hello".toByteArray())
                else -> raw(200, "application/octet-stream", ByteArray(0))
            }
        }
        when {
            path == "/v1/ping" -> reply(200, "OK", JSONObject().put("ok", true))
            path == "/v1/ca" && caMode == "500" -> reply(500, "Error", JSONObject().put("error", "boom"))
            path == "/v1/ca" && caMode == "bad" ->
                reply(200, "OK", JSONObject().put("pem", "-----BEGIN CERTIFICATE-----\nnot*base64!\n-----END CERTIFICATE-----\n"))
            path == "/v1/ca" -> reply(200, "OK", JSONObject().put("pem", pem))
            else -> reply(200, "OK", JSONObject().put("ok", true).put("path", path))
        }
    }

    private class Result(val code: Int, val out: String, val err: String)

    /** A PATH holding ONLY the tools browserctl needs, so a host's own update-ca-certificates (Ubuntu has one) can
     *  never leak into the test; [extra] adds fake tools (e.g. a recording update-ca-certificates). */
    private fun toolPath(extra: Map<String, String> = emptyMap()): String {
        val bin = File(work, "bin").apply { mkdirs() }
        for (tool in listOf("wget", "sed", "tr", "grep", "mkdir", "cat", "base64", "mktemp", "od", "head", "wc", "rm", "date")) {
            val real = listOf("/usr/bin", "/bin", "/usr/local/bin").map { File(it, tool) }.firstOrNull { it.exists() } ?: continue
            val link = File(bin, tool)
            if (!link.exists()) java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
        }
        for ((name, body) in extra) File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
        return bin.absolutePath
    }

    private fun run(vararg args: String, token: String = "tok123", env: Map<String, String> = emptyMap(), fakeTools: Map<String, String> = emptyMap()): Result =
        exec(listOf("/bin/sh", script().absolutePath) + args, token, env, fakeTools)

    private fun exec(cmd: List<String>, token: String, env: Map<String, String>, fakeTools: Map<String, String> = emptyMap()): Result {
        val pb = ProcessBuilder(cmd)
        pb.environment().apply {
            put("PATH", toolPath(fakeTools))
            put("BROWSERCTL_CADIR", File(work, "cadir").absolutePath)
            put("ALPBROWSER_PORT", port.toString())
            if (token.isNotEmpty()) put("ALPBROWSER_TOKEN", token) else remove("ALPBROWSER_TOKEN")
            put("BROWSERCTL_BUNDLE", File(work, "bundle.crt").absolutePath)
            put("BROWSERCTL_ENV", File(work, "alpbrowser.env").absolutePath)
            putAll(env)
        }
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("browserctl timed out", p.waitFor(30, TimeUnit.SECONDS))
        return Result(p.exitValue(), out, err)
    }

    private fun lastPost(): JSONObject = JSONObject(posts.last().second)

    @Test fun pingSucceeds() {
        val r = run("ping")
        assertEquals(0, r.code)
        assertTrue(r.out.contains("\"ok\":true"))
    }

    @Test fun failedRequestsHaveANonZeroExitStatus() {
        assertNotEquals("wrong token (401) must not look like success", 0, run("ping", token = "wrong").code)
        server.close()
        assertNotEquals("unreachable server must not look like success", 0, run("ping").code)
    }

    @Test fun caIsReturnedByteForByteDespiteEscapedSlashes() {
        val r = run("ca")
        assertEquals(0, r.code)
        assertEquals(pem.trimEnd(), r.out.trimEnd())
        assertFalse("a leftover backslash means a corrupted certificate", r.out.contains("\\"))
    }

    @Test fun trustAppendsTheCaOnceAndOnlyOnce() {
        val bundle = File(work, "bundle.crt")
        bundle.writeText("")
        assertEquals(0, run("trust").code)
        assertEquals(0, run("trust").code)
        assertEquals(pem.trimEnd(), bundle.readText().trimEnd())
        assertEquals(1, Regex("BEGIN CERTIFICATE").findAll(bundle.readText()).count())
    }

    @Test fun trustPrefersUpdateCaCertificatesWhenTheSystemHasIt() {
        val bundle = File(work, "bundle.crt")
        bundle.writeText("existing\n")
        val marker = File(work, "update-ran")
        val r = run("trust", fakeTools = mapOf("update-ca-certificates" to "echo ran > ${marker.absolutePath}"))
        assertEquals(r.err, 0, r.code)
        assertTrue("the real tool must be run so the cert survives `apk upgrade ca-certificates`", marker.exists())
        assertEquals(pem.trimEnd(), File(work, "cadir/alpbrowser.crt").readText().trimEnd())
        assertEquals("the bundle itself is left to the tool", "existing\n", bundle.readText())
    }

    @Test fun trustFailsWhenUpdateCaCertificatesFails() {
        val r = run("trust", fakeTools = mapOf("update-ca-certificates" to "exit 1"))
        assertNotEquals("a failing update tool must not be reported as success", 0, r.code)
    }

    @Test fun trustNeverClaimsSuccessWhenTheCaCouldNotBeFetched() {
        val bundle = File(work, "bundle.crt")
        bundle.writeText("existing\n")
        caMode = "500"
        assertNotEquals(0, run("trust").code)
        caMode = "bad"
        assertNotEquals("a malformed certificate must be refused", 0, run("trust").code)
        assertEquals("the bundle must be untouched on failure", "existing\n", bundle.readText())
    }

    @Test fun argumentsOfAnyShapeBecomeValidJson() {
        val tricky = listOf(
            "multi\nline",
            "tab\there",
            "quote \" and backslash \\ together",
            "slashes // and 'single' quotes",
            "x",
        )
        for (t in tricky) {
            posts.clear()
            val r = run("eval", t)
            assertEquals("eval of [$t] failed: ${r.err}", 0, r.code)
            assertEquals(t, lastPost().getString("js"))
        }
    }

    @Test fun unicodeSurvivesIntoTheJsonBody() {
        val text = "unicode: \u0645\u0631\u062d\u0628\u0627 \u4f60\u597d"
        // Passed from inside a shell via printf: the JVM would turn non-ASCII argv bytes into '?' under a POSIX locale.
        val octal = text.toByteArray(Charsets.UTF_8).joinToString("") { "\\%03o".format(it.toInt() and 0xff) }
        val r = exec(
            listOf("/bin/sh", "-c", "ARG=\$(printf '$octal'); exec /bin/sh \"\$0\" eval \"\$ARG\"", script().absolutePath),
            "tok123", emptyMap(),
        )
        assertEquals(r.err, 0, r.code)
        assertEquals(text, lastPost().getString("js"))
    }

    @Test fun anEmptyReplacementIsSentAsAnEmptyString() {
        val r = run("rule", "add", "RES_BODY", "foo", "")
        assertEquals(r.err, 0, r.code)
        val body = lastPost()
        assertEquals("foo", body.getString("find"))
        assertEquals("", body.getString("replace"))
    }

    @Test fun anInjectionAttemptStaysAnOrdinaryString() {
        val r = run("resolve", "h1\",\"x\":\"y", "forward")
        assertEquals(r.err, 0, r.code)
        val body = lastPost()
        assertEquals("h1\",\"x\":\"y", body.getString("holdId"))
        assertFalse(body.has("x"))
    }

    @Test fun badEnumsAndUnsafeValuesAreRejectedBeforeAnyRequest() {
        posts.clear()
        for (args in listOf(
            arrayOf("mode", "nonsense"), arrayOf("clear", "bogus"), arrayOf("rule", "add", "BOGUS", "a", "b"),
            arrayOf("get", "a/../b"), arrayOf("history", "x&y=1"), arrayOf("console", "abc"), arrayOf("resolve", "h1", "maybe"),
        )) {
            assertEquals("${args.toList()} should be a usage error", 2, run(*args).code)
        }
        assertTrue("nothing may have reached the server", posts.isEmpty())
    }

    @Test fun theEnvFileIsParsedNotExecuted() {
        val marker = File(work, "pwned")
        File(work, "alpbrowser.env").writeText(
            "ALPBROWSER_TOKEN=tok123\nALPBROWSER_PORT=$port\n\$(touch ${marker.absolutePath})\ntouch ${marker.absolutePath}\n",
        )
        assertEquals(0, run("ping", token = "", env = mapOf("ALPBROWSER_PORT" to "")).code)
        assertFalse("code in the env file must never run", marker.exists())

        File(work, "alpbrowser.env").writeText("ALPBROWSER_TOKEN=abc; touch ${marker.absolutePath}\n")
        assertEquals("a malformed token is refused", 3, run("ping", token = "").code)
        assertFalse(marker.exists())
    }

    @Test fun withoutATokenItSaysHowToLinkAndExits3() {
        val r = run("ping", token = "")
        assertEquals(3, r.code)
        assertTrue(r.err.contains("not linked"))
    }

    private fun lastPost(path: String): JSONObject = JSONObject(posts.last { it.first == path }.second)

    @Test fun resendBuildsTheRepeaterRequest() {
        val r = run("resend", "POST", "https://t.test/a?b=1", "--header", "Content-Type: application/json", "--header", "X-A: b: c",
            "--body", "{\"k\":\"line1\nline2\"}")
        assertEquals(r.err, 0, r.code)
        val j = lastPost("/v1/resend")
        assertEquals("POST", j.getString("method"))
        assertEquals("https://t.test/a?b=1", j.getString("url"))
        assertEquals("X-A", j.getJSONArray("headers").getJSONObject(1).getString("name"))
        assertEquals("b: c", j.getJSONArray("headers").getJSONObject(1).getString("value"))
        assertEquals("{\"k\":\"line1\nline2\"}", String(java.util.Base64.getDecoder().decode(j.getString("body"))))
    }

    @Test fun resendRefusesNonHttpUrls() {
        assertNotEquals(0, run("resend", "GET", "file:///etc/passwd").code)
        assertTrue(posts.none { it.first == "/v1/resend" })
    }

    @Test fun resolveEditSendsOnlyTheRequestedChanges() {
        val r = run("resolve", "h7", "edit", "--method", "PUT", "--status", "403", "--header", "X-Test: 1", "--body", "hi")
        assertEquals(r.err, 0, r.code)
        val j = lastPost("/v1/intercept/resolve")
        assertEquals("h7", j.getString("holdId"))
        assertEquals("edit", j.getString("action"))
        assertEquals("PUT", j.getString("method"))
        assertEquals(403, j.getInt("status"))
        assertEquals("X-Test", j.getJSONArray("setHeaders").getJSONObject(0).getString("name"))
        assertFalse(j.has("target"))
        assertEquals("hi", String(java.util.Base64.getDecoder().decode(j.getString("body"))))
    }

    @Test fun ruleHeaderAddsAHeaderRule() {
        assertEquals(0, run("rule", "header", "req", "X-Bunny: yes", "target.test").code)
        val j = lastPost("/v1/rules")
        assertEquals("REQ_HEADER", j.getString("part"))
        assertEquals("", j.getString("find"))
        assertEquals("X-Bunny: yes", j.getString("replace"))
        assertEquals("target.test", j.getString("hostContains"))
        assertNotEquals(0, run("rule", "header", "req", "no-colon").code)
    }

    @Test fun captureTabsAndInterceptOptionsSendTheRightRequests() {
        assertEquals(0, run("capture", "on").code)
        assertEquals(true, lastPost("/v1/capture").getBoolean("enabled"))
        assertNotEquals(0, run("capture", "maybe").code)

        assertEquals(0, run("tab", "new", "https://x.test/a b").code)
        assertEquals("new", lastPost("/v1/tabs").getString("action"))
        assertEquals("https://x.test/a b", lastPost("/v1/tabs").getString("url"))
        assertEquals(0, run("tab", "switch", "3").code)
        assertEquals(3, lastPost("/v1/tabs").getInt("id"))
        assertNotEquals(0, run("tab", "close", "x").code)

        assertEquals(0, run("intercept", "on", "target.test", "--responses", "--all-assets").code)
        val j = lastPost("/v1/intercept")
        assertEquals("target.test", j.getString("hostFilter"))
        assertEquals(true, j.getBoolean("responses"))
        assertEquals(false, j.getBoolean("skipStatic"))
        assertEquals(0, run("intercept", "on").code)
        assertEquals(false, lastPost("/v1/intercept").getBoolean("responses"))
        assertEquals(true, lastPost("/v1/intercept").getBoolean("skipStatic"))
    }

    // ---------------------------------------------------------------------------- automation commands

    @Test fun elementCommandsSendStructuredRequestsAndExitZero() {
        val r = run("click", "#go", "--timeout", "2500")
        assertEquals(r.err, 0, r.code)
        val j = lastPost("/v1/act")
        assertEquals("click", j.getString("cmd")); assertEquals("#go", j.getString("selector")); assertEquals(2500, j.getInt("timeout"))

        assertEquals(0, run("fill", "#f", "he said \"hi\"\nline2").code)
        assertEquals("he said \"hi\"\nline2", lastPost("/v1/act").getString("text"))

        assertEquals(0, run("type", "#f", "abc", "--delay", "30").code)
        assertEquals(30, lastPost("/v1/act").getInt("delay"))
        assertEquals(0, run("press", "Enter").code)
        assertEquals("Enter", lastPost("/v1/act").getString("key"))
        assertEquals(0, run("attr", "a.x", "href").code)
        assertEquals("href", lastPost("/v1/act").getString("name"))
        assertEquals(0, run("waitfor", "#late", "--state", "hidden", "--timeout", "500").code)
        assertEquals("hidden", lastPost("/v1/act").getString("state"))
    }

    @Test fun aFailedElementCommandIsNeverASuccess() {
        for (cmd in listOf(listOf("click", "#missing"), listOf("waitfor", "#missing", "--timeout", "10"), listOf("fill", "#missing", "x"), listOf("text", "#missing"))) {
            val r = run(*cmd.toTypedArray())
            assertEquals("$cmd must fail", 1, r.code)
            assertTrue(r.err, r.err.contains("#missing") && r.err.contains("timeout"))
            assertEquals("nothing on stdout that looks like success", "", r.out.trim())
        }
        assertEquals(2, run("click").code)
        assertEquals(2, run("waitfor", "#x", "--timeout", "soon").code)
    }

    @Test fun existsReportsThroughItsExitStatus() {
        // The mock echoes {"ok":true,...} without an "exists" field, i.e. "not found".
        assertEquals(1, run("exists", "#nothing").code)
    }

    @Test fun navigateWaitsOnlyWhenAsked() {
        assertEquals(0, run("navigate", "https://t.test/", "--wait", "--state", "networkidle", "--timeout", "9000").code)
        val j = lastPost("/v1/navigate")
        assertEquals(true, j.getBoolean("wait")); assertEquals("networkidle", j.getString("state")); assertEquals(9000, j.getInt("timeout"))
        assertEquals(0, run("navigate", "https://t.test/").code)
        assertFalse(lastPost("/v1/navigate").has("wait"))
        assertEquals(2, run("navigate", "https://t.test/", "--state", "whenever").code)
        assertEquals(0, run("waitnav", "--timeout", "4000").code)
        assertEquals(4000, lastPost("/v1/waitnav").getInt("timeout"))
    }

    @Test fun evalReadsAFileWithTemplateLiteralsNonAsciiAndHugeSize() {
        val script = File(work, "big.js")
        val line = "const a = `value: \${1 + 1} \"q\" \\ back`; // \u0627\u0644\u0639\u0631\u0628\u064a\u0629 caf\u00e9\n"
        script.writeText(line.repeat(4000)) // ~300 KB: far beyond one command-line argument
        val r = run("eval", "--file", script.absolutePath)
        assertEquals(r.err, 0, r.code)
        assertEquals(script.readText().trimEnd('\n'), lastPost("/v1/eval").getString("js"))
        val lines = lastPost("/v1/eval").getString("js").lines()
        assertTrue(lines.size >= 4000 && lines[0].contains("\${1 + 1}") && lines[0].contains("\u0627\u0644\u0639\u0631\u0628\u064a\u0629"))
    }

    @Test fun evalReadsStdinAndKeepsPlainArgumentsWorking() {
        val r = exec(listOf("/bin/sh", "-c", "printf 'let x = `a\\nb`;\\nx' | /bin/sh \"\$0\" eval --stdin --await --timeout 7000", script().absolutePath), "tok123", emptyMap())
        assertEquals(r.err, 0, r.code)
        val j = lastPost("/v1/eval")
        assertEquals("let x = `a\nb`;\nx", j.getString("js")); assertEquals(true, j.getBoolean("await")); assertEquals(7000, j.getInt("timeout"))
        assertEquals(0, run("eval", "1 + 1").code)
        assertEquals("1 + 1", lastPost("/v1/eval").getString("js"))
        assertEquals(2, run("eval").code)
    }

    @Test fun shotWritesTheImageAndFailsLoudlyOtherwise() {
        val out = File(work, "a.png")
        val r = run("shot", "--out", out.absolutePath, "--full-page")
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out.contains("\"bytes\":12"))
        assertEquals(0x89, out.readBytes()[0].toInt() and 0xff)
        assertTrue(gets.any { it.contains("fullPage=1") && it.contains("format=png") })
        val bad = run("shot", "--selector", "#missing", "--out", File(work, "b.png").absolutePath)
        assertEquals(1, bad.code)
        assertTrue(bad.err, bad.err.contains("no element matches"))
        assertFalse("no half-written file is left behind", File(work, "b.png").exists())
        assertEquals(2, run("shot", "--format", "gif").code)
    }

    @Test fun cdpPrintsTheWebSocketUrlWithTheTokenInIt() {
        val r = run("cdp")
        assertEquals(r.err, 0, r.code)
        assertEquals("ws://127.0.0.1:9222/tok/devtools/browser/abc", r.out.trim())
        assertEquals(true, lastPost("/v1/cdp").getBoolean("enabled"))
        run("cdp", "9333"); assertEquals(9333, lastPost("/v1/cdp").getInt("port"))
        assertEquals("0", run("cdp", "off").out.trim())
        assertEquals(false, lastPost("/v1/cdp").getBoolean("enabled"))
        assertEquals(2, run("cdp", "banana").code)
    }

    @Test fun saveWritesReplayableFiles() {
        val dir = File(work, "evidence")
        val r = run("save", "abc", "--dir", dir.absolutePath)
        assertEquals(r.err, 0, r.code)
        assertTrue(File(dir, "request.txt").readText().startsWith("GET /x HTTP/1.1"))
        assertEquals("HTTP/1.1 200\n\nhello", File(dir, "response.txt").readText())
        assertEquals("hello", File(dir, "response.body").readText())
        assertFalse("an empty request body is not written", File(dir, "request.body").exists())
        assertTrue(File(dir, "meta.json").readText().contains("abc"))
        assertEquals(2, run("save", "abc").code)
    }

    @Test fun historyFiltersBecomeAQuery() {
        assertEquals(0, run("history", "--host", "t.test", "--method", "POST", "--status", "403", "--since", "2026-10-11T10:00:00Z", "--limit", "5", "--page", "2", "--compact").code)
        val q = gets.last()
        for (part in listOf("host=t.test", "method=POST", "status=403", "since=2026-10-11T10:00:00Z", "limit=5", "page=2", "fmt=lines")) assertTrue("$part in $q", q.contains(part))
        assertEquals(0, run("history", "legacy.test").code)
        assertTrue(gets.last().contains("host=legacy.test"))
    }

    @Test fun cookiesCanBeReadAndSet() {
        assertEquals(0, run("cookies", "--domain", "t.test").code)
        assertTrue(gets.last().contains("domain=t.test"))
        assertEquals(0, run("setcookie", "sid", "abc 123", "--domain", "t.test", "--path", "/app").code)
        val j = lastPost("/v1/cookies")
        assertEquals("sid", j.getString("name")); assertEquals("abc 123", j.getString("value")); assertEquals("/app", j.getString("path"))
    }
}
