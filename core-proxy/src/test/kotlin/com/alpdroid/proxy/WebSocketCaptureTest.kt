package com.alpdroid.proxy

import java.io.ByteArrayOutputStream
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketCaptureTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val servers = ArrayList<ServerSocket>()

    @AfterTest fun tearDown() { servers.forEach { runCatching { it.close() } }; scope.cancel() }

    /** One WebSocket frame as bytes. */
    private fun frame(opcode: Int, payload: ByteArray, masked: Boolean, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        val m = if (masked) 0x80 else 0
        when {
            payload.size < 126 -> out.write(m or payload.size)
            payload.size < 65536 -> { out.write(m or 126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
            else -> { out.write(m or 127); for (s in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr s) and 0xff).toInt()) }
        }
        if (masked) {
            val key = byteArrayOf(1, 2, 3, 4)
            out.write(key)
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor key[it % 4].toInt()).toByte() })
        } else out.write(payload)
        return out.toByteArray()
    }

    private fun parse(fromClient: Boolean, bytes: ByteArray, chunk: Int = bytes.size.coerceAtLeast(1)): Pair<List<WsFrame>, WsFrameParser> {
        val got = ArrayList<WsFrame>()
        val p = WsFrameParser(fromClient, payloadCap = 64) { got += it }
        var i = 0
        while (i < bytes.size) { val n = minOf(chunk, bytes.size - i); p.feed(bytes, i, n); i += n }
        return got to p
    }

    @Test fun `masked client frames are unmasked and server frames read as they are`() {
        val (c, _) = parse(true, frame(1, "hello".toByteArray(), masked = true))
        assertEquals("hello", String(c.single().payload)); assertTrue(c.single().fromClient); assertEquals("text", c.single().opcodeName)
        val (s, _) = parse(false, frame(2, byteArrayOf(1, 2, 3), masked = false))
        assertEquals(listOf<Byte>(1, 2, 3), s.single().payload.toList()); assertEquals("binary", s.single().opcodeName)
    }

    @Test fun `several frames, split at every possible place, come out the same`() {
        val bytes = frame(1, "one".toByteArray(), true) + frame(9, ByteArray(0), true) + frame(1, ByteArray(300) { 'x'.code.toByte() }, true) + frame(8, byteArrayOf(3, 232.toByte()), true)
        for (chunk in listOf(1, 2, 3, 7, 100, bytes.size)) {
            val (f, p) = parse(true, bytes, chunk)
            assertFalse(p.broken)
            assertEquals(listOf("text", "ping", "text", "close"), f.map { it.opcodeName }, "chunk $chunk")
            assertEquals(300L, f[2].length)
        }
    }

    @Test fun `long payloads are cut to the cap and say so`() {
        val (f, _) = parse(false, frame(2, ByteArray(70_000) { 7 }, masked = false), chunk = 1000)
        assertEquals(70_000L, f.single().length); assertEquals(64, f.single().payload.size); assertTrue(f.single().truncated)
    }

    @Test fun `fragmented messages keep their fin flags`() {
        val (f, _) = parse(true, frame(1, "ab".toByteArray(), true, fin = false) + frame(0, "cd".toByteArray(), true, fin = true))
        assertEquals(listOf(false, true), f.map { it.fin }); assertEquals(listOf("text", "continuation"), f.map { it.opcodeName })
    }

    @Test fun `bytes that are not WebSocket make the parser give up without throwing`() {
        parse(false, "HTTP/1.1 200 OK\r\n\r\nnot a frame".toByteArray()) // must simply not throw, whatever it makes of it
        parse(true, ByteArray(2000) { (it * 31).toByte() })
        val (_, p2) = parse(false, byteArrayOf(0x83.toByte(), 0)) // an opcode that does not exist
        assertTrue(p2.broken)
    }

    @Test fun `the frames of a connection are bounded`() {
        val fr = WebSocketFrames("websocket", maxFrames = 5)
        repeat(20) { fr.add(true, 1, true, 1, byteArrayOf(it.toByte()), false) }
        assertEquals(20, fr.total)
        assertEquals((15..19).toList(), fr.snapshot().map { it.index })
        assertEquals(listOf(18, 19), fr.snapshot(since = 18).map { it.index })
        val reg = WebSocketRegistry(max = 2)
        reg.open("a", "websocket"); reg.open("b", "sse"); reg.open("c", "websocket")
        assertEquals(null, reg["a"]); assertEquals("sse", reg["b"]!!.kind)
    }

    @Test fun `server-sent events are split at blank lines, in any chunking`() {
        val body = "data: one\n\nevent: tick\ndata: two\r\ndata: lines\r\n\r\n: comment\n\ndata: tail"
        for (chunk in listOf(1, 3, body.length)) {
            val got = ArrayList<String>()
            val p = SseParser { t, _, _ -> got += t }
            val b = body.toByteArray(); var i = 0
            while (i < b.size) { val n = minOf(chunk, b.size - i); p.feed(b, i, n); i += n }
            assertEquals(listOf("data: one", "event: tick\ndata: two\ndata: lines", ": comment"), got, "chunk $chunk")
        }
    }

    // ------------------------------------------------------------------------------------------ through the real proxy

    private fun engine(store: TransactionStore): ProxyEngine {
        val e = ProxyEngine(0, TrafficSource.BROWSER, store, ScopeFilter(), respectScope = false)
        e.start(scope); Thread.sleep(150); return e
    }

    @Test fun `a websocket through the proxy is relayed untouched and its frames are kept`() {
        var upstreamSaw = ""
        val s = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            runCatching {
                s.accept().use { c ->
                    val r = c.getInputStream(); val head = StringBuilder(); var tail = 0
                    while (tail != 0x0d0a0d0a) { val b = r.read(); if (b < 0) return@use; head.append(b.toChar()); tail = (tail shl 8) or b }
                    upstreamSaw = head.toString()
                    c.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: x\r\n\r\n".toByteArray())
                    c.getOutputStream().write(frame(1, "welcome".toByteArray(), masked = false)); c.getOutputStream().flush()
                    // Read one client frame (masked, 4 bytes payload), answer with an echo.
                    val hdr = ByteArray(2); r.readNBytes(hdr, 0, 2); val len = hdr[1].toInt() and 0x7f
                    val rest = ByteArray(4 + len); r.readNBytes(rest, 0, rest.size)
                    val payload = ByteArray(len) { (rest[4 + it].toInt() xor rest[it % 4].toInt()).toByte() }
                    c.getOutputStream().write(frame(1, "echo:".toByteArray() + payload, masked = false)); c.getOutputStream().flush()
                    Thread.sleep(300)
                }
            }
        }
        val store = TransactionStore()
        val proxy = engine(store)
        Socket("127.0.0.1", proxy.boundPort).use { c ->
            c.soTimeout = 5000
            c.getOutputStream().write(("GET http://127.0.0.1:${s.localPort}/ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: k\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n").toByteArray())
            val inp = c.getInputStream(); var tail = 0; val head = StringBuilder()
            while (tail != 0x0d0a0d0a) { val b = inp.read(); head.append(b.toChar()); tail = (tail shl 8) or b }
            assertTrue(head.startsWith("HTTP/1.1 101"), head.toString())
            val first = ByteArray(2 + 7); inp.readNBytes(first, 0, first.size)
            c.getOutputStream().write(frame(1, "ping".toByteArray(), masked = true)); c.getOutputStream().flush()
            val second = ByteArray(2 + 9); inp.readNBytes(second, 0, second.size)
            assertEquals("echo:ping", String(second, 2, 9))
        }
        Thread.sleep(300)
        assertTrue(upstreamSaw.contains("Connection: Upgrade", ignoreCase = true), "the Upgrade handshake must reach the server intact:\n$upstreamSaw")
        assertFalse(upstreamSaw.contains("Connection: close", ignoreCase = true), upstreamSaw)
        assertFalse(upstreamSaw.contains("permessage-deflate", ignoreCase = true), "the compression extension must not reach the server:\n$upstreamSaw")
        val tx = store.transactions.value.single { it.responseStatus == 101 }
        val frames = store.webSockets[tx.id]!!
        assertEquals("websocket", frames.kind)
        // The two directions are recorded by separate threads, so only the order within each direction is fixed; give a slow machine time to record all three.
        val deadline = System.currentTimeMillis() + 5000
        while (frames.snapshot().size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(25)
        val seen = frames.snapshot().map { String(it.payload) to it.fromClient }
        assertEquals(listOf("welcome", "echo:ping"), seen.filter { !it.second }.map { it.first }, seen.toString())
        assertEquals(listOf("ping"), seen.filter { it.second }.map { it.first }, seen.toString())
    }

    @Test fun `a server-sent event stream reaches the client while it is still open, and its events are kept`() {
        val s = ServerSocket(0).also { servers += it }
        val release = java.util.concurrent.CountDownLatch(1)
        thread(isDaemon = true) {
            runCatching {
                s.accept().use { c ->
                    val r = c.getInputStream(); var tail = 0
                    while (tail != 0x0d0a0d0a) { val b = r.read(); if (b < 0) return@use; tail = (tail shl 8) or b }
                    val o = c.getOutputStream()
                    o.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    val e1 = "data: first\n\n"
                    o.write("${Integer.toHexString(e1.length)}\r\n$e1\r\n".toByteArray()); o.flush()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS) // the stream stays open until the test has seen event one
                    val e2 = "data: second\n\n"
                    o.write("${Integer.toHexString(e2.length)}\r\n$e2\r\n0\r\n\r\n".toByteArray()); o.flush()
                }
            }
        }
        val store = TransactionStore()
        val proxy = engine(store)
        Socket("127.0.0.1", proxy.boundPort).use { c ->
            c.soTimeout = 5000
            c.getOutputStream().write("GET http://127.0.0.1:${s.localPort}/events HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n".toByteArray())
            val inp = c.getInputStream(); val seen = StringBuilder()
            while (!seen.contains("data: first")) { val b = inp.read(); if (b < 0) break; seen.append(b.toChar()) }
            assertTrue(seen.contains("HTTP/1.1 200") && seen.contains("data: first"), "the first event must arrive while the stream is still open:\n$seen")
            release.countDown()
            val rest = inp.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(rest.contains("data: second") && rest.contains("0\r\n\r\n"), rest)
        }
        Thread.sleep(300)
        val tx = store.transactions.value.single { it.responseHeaders.any { h -> h.second.startsWith("text/event-stream") } }
        assertEquals(listOf("data: first", "data: second"), store.webSockets[tx.id]!!.snapshot().map { String(it.payload) })
        assertEquals("sse", store.webSockets[tx.id]!!.kind)
    }
}
