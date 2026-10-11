package com.alpdroid.browser

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** The cookie reader against a real Chromium's DevTools over TCP (the protocol is the same one WebView speaks on its unix socket). Skipped without Playwright. */
class CdpClientTest {
    private var proc: Process? = null
    private var port = 0

    @Before fun start() {
        val nodePath = System.getenv("ALP_PLAYWRIGHT_NODE_PATH") ?: "/opt/node22/lib/node_modules"
        assumeTrue("Playwright is not installed", File(nodePath, "playwright").isDirectory)
        port = ServerSocket(0).use { it.localPort }
        val script = File.createTempFile("chromium_cookies", ".js").also { it.deleteOnExit() }
        script.writeText(javaClass.getResource("/chromium_cookies.js")!!.readText())
        val pb = ProcessBuilder("node", script.absolutePath, port.toString()).redirectErrorStream(true)
        pb.environment()["NODE_PATH"] = nodePath
        val p = pb.start().also { proc = it }
        val ready = CountDownLatch(1)
        Thread { p.inputStream.bufferedReader().forEachLine { if (it.contains("ready")) ready.countDown() } }.apply { isDaemon = true }.start()
        assumeTrue("Chromium did not start", ready.await(60, TimeUnit.SECONDS))
    }

    @After fun stop() { runCatching { proc?.outputStream?.close() }; proc?.destroyForcibly() }

    private fun client() = CdpClient {
        val s = Socket("127.0.0.1", port)
        CdpForwarder.Link(s.getInputStream(), s.getOutputStream(), { runCatching { s.close() } })
    }

    @Test fun `every attribute of a cookie is read`() {
        val all = client().cookies(null)
        val sid = (0 until all.length()).map { all.getJSONObject(it) }.single { it.getString("name") == "sid" }
        assertEquals("abc", sid.getString("value")); assertEquals("/app", sid.getString("path"))
        assertTrue(sid.getBoolean("secure") && sid.getBoolean("httpOnly")); assertEquals("Lax", sid.getString("sameSite"))
        // Chromium shortens a far-future expiry to at most 400 days from now.
        val now = System.currentTimeMillis() / 1000
        assertTrue(sid.getLong("expires") in (now + 300L * 86400)..(now + 401L * 86400)); assertTrue(!sid.getBoolean("session"))
        val pref = (0 until all.length()).map { all.getJSONObject(it) }.single { it.getString("name") == "pref" }
        assertTrue(!pref.getBoolean("secure") && !pref.getBoolean("httpOnly"))
    }

    @Test fun `a domain filter keeps only matching cookies`() {
        val only = client().cookies("example.test")
        assertEquals(listOf("sid"), (0 until only.length()).map { only.getJSONObject(it).getString("name") })
    }

    @Test fun `a connection that never answers ends with a clear error instead of hanging`() {
        val silent = ServerSocket(0)
        try {
            val c = CdpClient { val s = Socket("127.0.0.1", silent.localPort); CdpForwarder.Link(s.getInputStream(), s.getOutputStream(), { runCatching { s.close() } }) }
            val started = System.currentTimeMillis()
            val e = runCatching { c.cookies(null, timeoutMs = 700) }.exceptionOrNull()
            assertTrue(e is CdpClient.CdpException && e.message!!.contains("did not answer"))
            assertTrue(System.currentTimeMillis() - started < 5_000)
        } finally { silent.close() }
    }
}
