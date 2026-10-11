package com.alpdroid.proxy

import java.io.BufferedInputStream
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

    @Test fun `a gzip answer reaches the client compressed, but history keeps the readable text`() {
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write("hello httpbin".toByteArray()) } }.toByteArray()
        val server = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { c ->
                        val r = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        seen.put(generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n"))
                        c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n\r\n".toByteArray() + gz)
                    }
                } catch (_: Exception) { }
            }
        }
        val store = TransactionStore()
        val e = ProxyEngine(0, TrafficSource.BROWSER, store, ScopeFilter(), respectScope = false)
        e.start(scope); Thread.sleep(150)
        val out = Socket("127.0.0.1", e.boundPort).use { c ->
            c.soTimeout = 5000
            c.getOutputStream().write("GET http://127.0.0.1:${server.localPort}/x HTTP/1.1\r\nHost: h\r\nAccept-Encoding: gzip, br\r\n\r\n".toByteArray())
            c.getInputStream().readBytes()
        }
        val text = out.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Encoding: gzip", ignoreCase = true), text)
        assertTrue(out.copyOfRange(out.size - gz.size, out.size).contentEquals(gz))
        assertTrue(seen.poll(2, java.util.concurrent.TimeUnit.SECONDS)!!.contains("Accept-Encoding: gzip"))
        Thread.sleep(100)
        assertEquals("hello httpbin", store.transactions.value.single().responseBody!!.toString(Charsets.UTF_8))
    }

    @Test fun `an edited compressed answer is sent decoded with the edit applied`() {
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write("hello httpbin".toByteArray()) } }.toByteArray()
        val o = origin { "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n\r\n".toByteArray() + gz }
        val interceptor = object : ProxyInterceptor {
            override suspend fun onRequest(req: InterceptedRequest) = RequestAction.Forward
            override suspend fun onResponse(res: InterceptedResponse) =
                ResponseAction.Replace(body = res.body!!.toString(Charsets.UTF_8).replace("httpbin", "HTTPBIN-RULE-OK").toByteArray())
        }
        val e = ProxyEngine(0, TrafficSource.BROWSER, TransactionStore(), ScopeFilter(), respectScope = false, interceptor = interceptor)
        e.start(scope); Thread.sleep(150)
        val out = roundTrip(e.boundPort, "GET", o)
        assertTrue(out.endsWith("hello HTTPBIN-RULE-OK"), out)
        assertTrue(!out.contains("Content-Encoding", ignoreCase = true), out)
    }

    @Test fun `one tunnel carries several HTTPS requests over a single upstream connection`() {
        val mitm = freshTestMitm()
        val origin = mitm.serverContext("localhost").serverSocketFactory.createServerSocket(0).also { servers += it }
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        thread(isDaemon = true) {
            while (!origin.isClosed) {
                try {
                    val c = origin.accept(); accepted.incrementAndGet()
                    thread(isDaemon = true) {
                        c.use {
                            val r = BufferedInputStream(it.getInputStream())
                            while (true) {
                                readLine(r) ?: return@use
                                val h = readHeaders(r)
                                val body = "page-${h.size}".toByteArray()
                                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
                                it.getOutputStream().flush()
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        val trust = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", mitm.ca.certificate) }
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        val proxyMitm = TlsMitm(mitm.ca) { _, port ->
            ctx.socketFactory.createSocket(Socket("127.0.0.1", port), "localhost", port, true) as javax.net.ssl.SSLSocket
        }
        val store = TransactionStore()
        val e = ProxyEngine(0, TrafficSource.BROWSER, store, ScopeFilter(), respectScope = false, tlsMitmProvider = { proxyMitm })
        e.start(scope); Thread.sleep(150)

        Socket("127.0.0.1", e.boundPort).use { raw ->
            raw.soTimeout = 8000
            raw.getOutputStream().write("CONNECT localhost:${origin.localPort} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            raw.getOutputStream().flush()
            val rin = BufferedInputStream(raw.getInputStream())
            assertTrue(readLine(rin)!!.contains("200"))
            readHeaders(rin)
            val tls = ctx.socketFactory.createSocket(raw, "localhost", raw.port, true) as javax.net.ssl.SSLSocket
            tls.startHandshake()
            val tin = BufferedInputStream(tls.inputStream)
            repeat(3) { i ->
                tls.outputStream.write("GET /p$i HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()); tls.outputStream.flush()
                assertTrue(readLine(tin)!!.startsWith("HTTP/1.1 200"))
                val h = readHeaders(tin)
                val n = h.first { it.first.equals("Content-Length", true) }.second.toInt()
                assertTrue(!h.any { it.first.equals("Connection", true) && it.second.contains("close", true) })
                tin.readNBytes(n)
            }
        }
        Thread.sleep(150)
        assertEquals(1, accepted.get())
        assertEquals(3, store.transactions.value.count { !it.opaque })
    }

    @Test fun `accept-encoding is narrowed to what the proxy can decode`() {
        assertEquals(listOf("Accept-Encoding" to "gzip, deflate"), narrowAcceptEncoding(listOf("Accept-Encoding" to "gzip, deflate, br, zstd")))
        assertTrue(narrowAcceptEncoding(listOf("Accept-Encoding" to "br")).isEmpty())
        assertTrue(narrowAcceptEncoding(emptyList()).isEmpty())
    }

    @Test fun `the proxy counts the bytes it really moved`() {
        val o = origin { "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray() + ByteArray(1000) { 'x'.code.toByte() } }
        val stats = ProxyStats()
        val e = ProxyEngine(0, TrafficSource.BROWSER, TransactionStore(), ScopeFilter(), respectScope = false, stats = stats)
        e.start(scope); Thread.sleep(150)
        roundTrip(e.boundPort, "GET", o)
        Thread.sleep(100)
        assertTrue(stats.bytesDown.get() in 1000..1300, "down=${stats.bytesDown.get()}")
        assertTrue(stats.bytesUp.get() in 50..400, "up=${stats.bytesUp.get()}")
        assertEquals(1, stats.requests.get().toInt())
    }
}
