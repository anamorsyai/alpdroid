package com.alpdroid.browser

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Makes the WebView's DevTools (CDP) reachable as an ordinary loopback TCP port, the way `adb forward` would. WebView
 * only exposes DevTools on an abstract unix socket that a shell inside proot cannot open, so this listens on
 * 127.0.0.1 and relays to it ([connectUpstream]), including WebSocket upgrades, which is what Puppeteer/Playwright use.
 *
 * Never reachable without the control token: either `Authorization: Bearer <token>`, or the token as the first path
 * segment (`/<token>/json/version`, `/<token>/devtools/page/<id>`), which is stripped before the request is relayed.
 * The latter exists because CDP clients connect to a URL and cannot always add headers; the `/json` answers are
 * rewritten so the WebSocket URLs they list carry the token too.
 */
class CdpForwarder(
    private val token: String,
    private val connectUpstream: () -> Link,
    private val openAccess: () -> Boolean = { false },
) {
    /** An open connection to DevTools and how to close it. */
    class Link(val input: InputStream, val output: OutputStream, val close: () -> Unit = {})

    private companion object { const val READ_LIMIT_MS = 8_000L }

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "alp-cdp").apply { isDaemon = true } }

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start(preferredPort: Int): Int {
        server?.let { return it.localPort }
        val loopback = InetAddress.getByName("127.0.0.1") // never a non-loopback interface
        val s = runCatching { ServerSocket(preferredPort, 16, loopback) }.getOrElse { ServerSocket(0, 16, loopback) }
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { if (s.isClosed) break else { Thread.sleep(50); continue } }
                pool.execute { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }, "alp-cdp-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun readHead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (out.size() < 32 * 1024) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0d0a0d0a) return out.toByteArray()
        }
        return null
    }

    private fun tokenOk(presented: String): Boolean {
        if (presented.length != token.length) return false
        var d = 0
        for (i in token.indices) d = d or (presented[i].code xor token[i].code)
        return d == 0
    }

    private fun reply(out: OutputStream, code: Int, text: String) {
        val b = text.toByteArray()
        out.write("HTTP/1.1 $code ${if (code == 401) "Unauthorized" else if (code == 503) "Service Unavailable" else "Bad Request"}\r\nContent-Type: text/plain\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val cin = client.getInputStream()
        val cout = client.getOutputStream()
        val headBytes = readHead(cin) ?: return
        val lines = String(headBytes, Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(' ') ?: return
        if (parts.size != 3) return reply(cout, 400, "bad request")
        var path = parts[1]
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }
        fun h(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second

        val bearer = h("Authorization")?.removePrefix("Bearer ")?.trim()
        val prefix = "/$token"
        val firstSegment = path.removePrefix("/").substringBefore('/').substringBefore('?')
        var tokened = true
        val authed = when {
            bearer != null && tokenOk(bearer) -> true
            tokenOk(firstSegment) -> { path = path.removePrefix(prefix).ifEmpty { "/" }; true }
            openAccess() && LocalAccess.allowed(headers.associate { it.first.lowercase() to it.second }) -> { tokened = false; true }
            else -> false
        }
        if (!authed) return reply(cout, 401, "unauthorized: use the URL printed by `browserctl cdp`")

        val link = try { connectUpstream() } catch (e: Exception) {
            return reply(cout, 503, "DevTools is not available: run `browserctl cdp` (it turns remote debugging on) and make sure a page is open")
        }
        val uin = link.input
        val uout = link.output
        try {
            val upgrade = h("Upgrade")?.equals("websocket", true) == true
            val head = StringBuilder("${parts[0]} $path ${parts[2]}\r\n")
            headers.filterNot { it.first.equals("Authorization", true) || (!upgrade && it.first.equals("Connection", true)) }
                .forEach { head.append(it.first).append(": ").append(it.second).append("\r\n") }
            if (!upgrade) head.append("Connection: close\r\n")
            head.append("\r\n")
            uout.write(head.toString().toByteArray(Charsets.ISO_8859_1)); uout.flush()

            if (upgrade) {
                client.soTimeout = 0
                val t = Thread({ runCatching { pipe(uin, cout) }; runCatching { client.close() } }, "alp-cdp-down").apply { isDaemon = true }
                t.start()
                pipe(cin, uout)
                runCatching { client.close() }
                return
            }
            // A plain request: read the whole answer, then (for the /json listings) put the token into the WebSocket URLs.
            val raw = readResponse(uin, link)
            cout.write(if (path.startsWith("/json") && tokened) rewrite(raw) else raw)
            cout.flush()
        } finally { runCatching { link.close() } }
    }

    /**
     * Reads one HTTP response from DevTools: the head, then exactly `Content-Length` bytes. DevTools keeps the
     * connection open after answering (it ignores `Connection: close`), so waiting for the socket to close would never
     * return; a response without a length is read until the socket closes or [READ_LIMIT_MS] passes, whichever is first.
     */
    private fun readResponse(uin: InputStream, link: Link): ByteArray {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread({ Thread.sleep(READ_LIMIT_MS); if (!finished.get()) runCatching { link.close() } }, "alp-cdp-limit").apply { isDaemon = true }.start()
        try {
            val all = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var headEnd = -1
            var total = -1
            while (true) {
                val n = try { uin.read(buf) } catch (e: java.io.IOException) { -1 }
                if (n < 0) break
                all.write(buf, 0, n)
                if (headEnd < 0) {
                    val text = String(all.toByteArray(), Charsets.ISO_8859_1)
                    val i = text.indexOf("\r\n\r\n")
                    if (i >= 0) {
                        headEnd = i + 4
                        val cl = Regex("(?im)^Content-Length:\\s*(\\d+)").find(text.substring(0, i))?.groupValues?.get(1)?.toIntOrNull()
                        if (cl != null) total = headEnd + cl
                    }
                }
                if (total >= 0 && all.size() >= total) break
            }
            return all.toByteArray()
        } finally { finished.set(true) }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) { val n = from.read(buf); if (n < 0) break; to.write(buf, 0, n); to.flush() }
    }

    private val wsUrl = Regex("((?:ws://)?(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?)/devtools")

    /** Inserts `/<token>` into every `ws://host:port/devtools/...` in the body and fixes Content-Length. */
    internal fun rewrite(response: ByteArray): ByteArray {
        val text = String(response, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        if (split < 0) return response
        val head = text.substring(0, split)
        val body = text.substring(split + 4)
        val newBody = wsUrl.replace(body) { m -> "${m.groupValues[1]}/$token/devtools" }
        val newHead = head.split("\r\n").joinToString("\r\n") {
            if (it.startsWith("Content-Length:", true)) "Content-Length: ${newBody.toByteArray(Charsets.ISO_8859_1).size}" else it
        }
        return (newHead + "\r\n\r\n" + newBody).toByteArray(Charsets.ISO_8859_1)
    }
}
