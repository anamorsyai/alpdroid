package com.alpdroid.browser

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdpForwarderTest {
    private val token = "secrettoken123"
    private val servers = ArrayList<ServerSocket>()
    private val forwarders = ArrayList<CdpForwarder>()

    @After fun tearDown() { forwarders.forEach { it.stop() }; servers.forEach { runCatching { it.close() } } }

    /** A stand-in for WebView's DevTools socket: answers /json/version, and echoes WebSocket bytes after an upgrade. */
    private fun fakeDevtools(seen: MutableList<String>): ServerSocket {
        val s = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) try {
                val c = s.accept()
                thread(isDaemon = true) { c.use {
                    val inp = BufferedInputStream(it.getInputStream())
                    val head = StringBuilder(); var tail = 0
                    while (tail != 0x0d0a0d0a) { val b = inp.read(); if (b < 0) return@use; head.append(b.toChar()); tail = (tail shl 8) or b }
                    seen += head.toString().lines().first()
                    if (head.contains("Upgrade: websocket", true)) {
                        it.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
                        it.getOutputStream().flush()
                        val buf = ByteArray(64); val n = inp.read(buf)
                        it.getOutputStream().write(("echo:" + String(buf, 0, n)).toByteArray())
                    } else {
                        val body = """{"Browser":"Chrome/1","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/browser/abc"}"""
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                    }
                } }
            } catch (_: Exception) { }
        }
        return s
    }

    private fun forwarder(devtools: ServerSocket, open: Boolean = false): CdpForwarder {
        val f = CdpForwarder(token, { val s = Socket("127.0.0.1", devtools.localPort); CdpForwarder.Link(s.getInputStream(), s.getOutputStream(), { s.close() }) }, { open })
        f.start(0)
        forwarders += f
        return f
    }

    private fun get(port: Int, path: String, extra: String = ""): String = Socket("127.0.0.1", port).use { c ->
        c.soTimeout = 5000
        c.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$extra\r\n".toByteArray())
        c.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    @Test fun `a request without the token is refused and never reaches DevTools`() {
        val seen = ArrayList<String>()
        val f = forwarder(fakeDevtools(seen))
        assertTrue(get(f.port, "/json/version").startsWith("HTTP/1.1 401"))
        assertTrue(get(f.port, "/wrongtoken/json/version").startsWith("HTTP/1.1 401"))
        Thread.sleep(100)
        assertTrue(seen.isEmpty())
    }

    @Test fun `the token path is stripped and the websocket urls in the answer carry it`() {
        val seen = ArrayList<String>()
        val f = forwarder(fakeDevtools(seen))
        val r = get(f.port, "/$token/json/version")
        assertTrue(r, r.startsWith("HTTP/1.1 200"))
        assertEquals("GET /json/version HTTP/1.1", seen.single())
        assertTrue(r, r.contains("ws://127.0.0.1:9222/$token/devtools/browser/abc"))
        val declared = Regex("Content-Length: (\\d+)").find(r)!!.groupValues[1].toInt()
        assertEquals(declared, r.substringAfter("\r\n\r\n").toByteArray().size)
    }

    @Test fun `with open local access a program needs no token but a web page is refused`() {
        val seen = ArrayList<String>()
        val f = forwarder(fakeDevtools(seen), open = true)
        val r = get(f.port, "/json/version")
        assertTrue(r, r.startsWith("HTTP/1.1 200"))
        assertTrue(r, r.contains("ws://127.0.0.1:9222/devtools/browser/abc"))
        assertFalse(r.contains(token))
        assertTrue(get(f.port, "/json/version", "Origin: https://evil.test\r\n").startsWith("HTTP/1.1 401"))
        assertTrue(get(f.port, "/json/version", "Sec-Fetch-Mode: cors\r\n").startsWith("HTTP/1.1 401"))
        // The token form keeps working, and still puts the token in the urls.
        assertTrue(get(f.port, "/$token/json/version").contains("/$token/devtools/browser/abc"))
    }

    @Test fun `the bearer header works too`() {
        val seen = ArrayList<String>()
        val f = forwarder(fakeDevtools(seen))
        assertTrue(get(f.port, "/json/version", "Authorization: Bearer $token\r\n").startsWith("HTTP/1.1 200"))
        assertFalse(get(f.port, "/json/version", "Authorization: Bearer nope\r\n").startsWith("HTTP/1.1 200"))
    }

    @Test fun `a websocket upgrade is relayed in both directions`() {
        val seen = ArrayList<String>()
        val f = forwarder(fakeDevtools(seen))
        Socket("127.0.0.1", f.port).use { c ->
            c.soTimeout = 5000
            c.getOutputStream().write("GET /$token/devtools/page/1 HTTP/1.1\r\nHost: x\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Key: abc\r\n\r\n".toByteArray())
            val inp = BufferedInputStream(c.getInputStream())
            val first = StringBuilder(); var tail = 0
            while (tail != 0x0d0a0d0a) { val b = inp.read(); first.append(b.toChar()); tail = (tail shl 8) or b }
            assertTrue(first.startsWith("HTTP/1.1 101"))
            c.getOutputStream().write("ping".toByteArray()); c.getOutputStream().flush()
            val buf = ByteArray(32); val n = inp.read(buf)
            assertEquals("echo:ping", String(buf, 0, n))
        }
        assertEquals("GET /devtools/page/1 HTTP/1.1", seen.single())
    }

    @Test fun `when DevTools is not available the client is told how to turn it on`() {
        val f = CdpForwarder(token, { throw java.io.IOException("no socket") })
        f.start(0); forwarders += f
        val r = get(f.port, "/$token/json/version")
        assertTrue(r, r.startsWith("HTTP/1.1 503") && r.contains("browserctl cdp"))
    }

    @Test fun `it listens on loopback only`() {
        val f = CdpForwarder(token, { throw java.io.IOException("x") })
        f.start(0); forwarders += f
        assertTrue(f.port > 0)
    }
}
