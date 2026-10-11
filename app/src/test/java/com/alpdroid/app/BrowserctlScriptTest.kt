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
            return reply(200, "OK", JSONObject().put("ok", true).put("echo", parsed))
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
        for (tool in listOf("wget", "sed", "tr", "grep", "mkdir", "cat", "base64")) {
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
}
