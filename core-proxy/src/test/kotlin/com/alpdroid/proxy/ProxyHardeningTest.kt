package com.alpdroid.proxy

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProxyHardeningTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val servers = ArrayList<ServerSocket>()

    @AfterTest fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    /** A raw origin that answers every connection with [reply] (read the request head first). */
    private fun origin(reply: () -> ByteArray): Int {
        val s = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    thread(isDaemon = true) {
                        c.use {
                            val r = it.getInputStream()
                            var tail = 0
                            while (tail != 0x0d0a0d0a) { val b = r.read(); if (b < 0) return@use; tail = (tail shl 8) or b }
                            it.getOutputStream().write(reply()); it.getOutputStream().flush()
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        return s.localPort
    }

    private fun engine(): ProxyEngine {
        val e = ProxyEngine(0, TrafficSource.BROWSER, TransactionStore(), ScopeFilter(), respectScope = false)
        e.start(scope)
        Thread.sleep(150)
        return e
    }

    private fun roundTrip(proxyPort: Int, method: String, originPort: Int): String =
        Socket("127.0.0.1", proxyPort).use { c ->
            c.soTimeout = 5000
            c.getOutputStream().write("$method http://127.0.0.1:$originPort/x HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            c.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    @Test fun `a HEAD answer that advertises a length does not stall the relay`() {
        val o = origin { "HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n".toByteArray() }
        val started = System.currentTimeMillis()
        val out = roundTrip(engine().boundPort, "HEAD", o)
        assertTrue(out.startsWith("HTTP/1.1 200"), out)
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test fun `a 204 is relayed without waiting for a body`() {
        val o = origin { "HTTP/1.1 204 No Content\r\nContent-Length: 10\r\n\r\n".toByteArray() }
        val out = roundTrip(engine().boundPort, "GET", o)
        assertTrue(out.startsWith("HTTP/1.1 204"), out)
    }

    @Test fun `an interim 100 Continue from the origin is skipped`() {
        val o = origin { "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray() }
        val out = roundTrip(engine().boundPort, "GET", o)
        assertTrue(out.startsWith("HTTP/1.1 200") && out.endsWith("ok"), out)
    }

    @Test fun `a garbage origin yields a 502 and the proxy keeps serving`() {
        val bad = origin { ByteArray(0) }
        val good = origin { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray() }
        val e = engine()
        assertTrue(roundTrip(e.boundPort, "GET", bad).startsWith("HTTP/1.1 502"))
        assertTrue(roundTrip(e.boundPort, "GET", good).endsWith("ok"))
    }

    @Test fun `an endless header line is rejected instead of buffered forever`() {
        val input = java.io.ByteArrayInputStream(ByteArray(MAX_LINE_BYTES + 10) { 'a'.code.toByte() })
        assertFailsWith<java.io.IOException> { readLine(input) }
    }

    @Test fun `a lying Content-Length reads only what arrives`() {
        val body = readMessageBody("abc".byteInputStream(), listOf("Content-Length" to "20000000"), 25 * 1024 * 1024, false)
        assertEquals("abc", body.bytes!!.toString(Charsets.US_ASCII))
    }

    @Test fun `bodyless responses are recognised`() {
        assertTrue(responseHasNoBody("HEAD", 200))
        assertTrue(responseHasNoBody("GET", 304))
        assertTrue(!responseHasNoBody("GET", 200))
    }
}
