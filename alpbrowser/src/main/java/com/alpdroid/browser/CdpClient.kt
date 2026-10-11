package com.alpdroid.browser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small Chrome DevTools Protocol client: it talks to the browser's own DevTools endpoint (the same one `browserctl cdp`
 * exposes) to ask for things Android's WebView APIs do not give, such as every attribute of a cookie. [open] returns a
 * fresh connection to DevTools each time it is called. Blocking; every call has a deadline after which the connection is closed.
 */
class CdpClient(private val open: () -> CdpForwarder.Link) {
    class CdpException(message: String) : IOException(message)

    private val random = SecureRandom()

    /** Cookies with all their attributes, optionally only those whose domain matches [domain]. Throws [CdpException] when DevTools cannot answer. */
    fun cookies(domain: String?, timeoutMs: Long = 8_000): JSONArray {
        val raw: JSONArray = try {
            // The browser-level endpoint lists every cookie of the default context.
            call(browserPath(timeoutMs), "Storage.getCookies", null, timeoutMs).optJSONArray("cookies") ?: JSONArray()
        } catch (first: IOException) {
            // Older or reduced DevTools builds only offer Network.* on a page target.
            val page = pagePath(timeoutMs) ?: throw first
            call(page, "Network.getAllCookies", null, timeoutMs).optJSONArray("cookies") ?: JSONArray()
        }
        val out = JSONArray()
        val want = domain?.removePrefix(".")?.lowercase()
        for (i in 0 until raw.length()) {
            val c = raw.getJSONObject(i)
            val d = c.optString("domain").removePrefix(".").lowercase()
            if (want != null && d != want && !d.endsWith(".$want") && !want.endsWith(".$d")) continue
            val expires = c.optDouble("expires", -1.0)
            out.put(JSONObject()
                .put("name", c.optString("name")).put("value", c.optString("value"))
                .put("domain", c.optString("domain")).put("path", c.optString("path", "/"))
                .put("secure", c.optBoolean("secure")).put("httpOnly", c.optBoolean("httpOnly"))
                .put("sameSite", if (c.has("sameSite")) c.optString("sameSite") else JSONObject.NULL)
                .put("session", c.optBoolean("session", expires < 0))
                .put("expires", if (expires < 0) JSONObject.NULL else expires.toLong()))
        }
        return out
    }

    private fun browserPath(timeoutMs: Long): String {
        val j = JSONObject(httpGet("/json/version", timeoutMs))
        return wsPath(j.optString("webSocketDebuggerUrl")) ?: "/devtools/browser"
    }

    private fun pagePath(timeoutMs: Long): String? {
        val arr = JSONArray(httpGet("/json/list", timeoutMs))
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optString("type") == "page") wsPath(t.optString("webSocketDebuggerUrl"))?.let { return it }
        }
        return null
    }

    private fun wsPath(url: String): String? = Regex("^wss?://[^/]+(/.*)$").find(url)?.groupValues?.get(1)

    private inline fun <T> withDeadline(link: CdpForwarder.Link, timeoutMs: Long, block: () -> T): T {
        val done = AtomicBoolean(false)
        val watchdog = Thread({ try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) { return@Thread }; if (!done.get()) runCatching { link.close() } }, "alp-cdp-client-limit").apply { isDaemon = true; start() }
        try { return block() } catch (e: IOException) {
            throw if (done.get()) e else CdpException("DevTools did not answer within ${timeoutMs}ms (${e.message})")
        } finally { done.set(true); watchdog.interrupt(); runCatching { link.close() } }
    }

    private fun httpGet(path: String, timeoutMs: Long): String {
        val link = open()
        return withDeadline(link, timeoutMs) {
            link.output.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()); link.output.flush()
            val head = readHead(link.input)
            val len = Regex("(?im)^Content-Length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull()
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) throw CdpException("DevTools answered: ${head.lineSequence().first()}")
            val body = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (len == null || body.size() < len) { val n = link.input.read(buf); if (n < 0) break; body.write(buf, 0, n) }
            String(body.toByteArray(), Charsets.UTF_8)
        }
    }

    /** Sends one command over a WebSocket to [path] and returns its `result`. */
    private fun call(path: String, method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        val link = open()
        return withDeadline(link, timeoutMs) {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
            link.output.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n".toByteArray())
            link.output.flush()
            val head = readHead(link.input)
            if (!head.startsWith("HTTP/1.1 101")) throw CdpException("DevTools refused the connection: ${head.lineSequence().first()}")
            val req = JSONObject().put("id", 1).put("method", method).also { if (params != null) it.put("params", params) }
            writeFrame(link, 1, req.toString().toByteArray(Charsets.UTF_8))
            while (true) {
                val (op, payload) = readMessage(link)
                if (op == 8) throw CdpException("DevTools closed the connection")
                if (op != 1) continue
                val msg = JSONObject(String(payload, Charsets.UTF_8))
                if (msg.optInt("id", -1) != 1) continue          // an event, not our answer
                msg.optJSONObject("error")?.let { throw CdpException(it.optString("message", "DevTools error")) }
                return@withDeadline msg.optJSONObject("result") ?: JSONObject()
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder(); var tail = 0
        while (tail != 0x0d0a0d0a) {
            val b = input.read(); if (b < 0) throw CdpException("DevTools closed the connection")
            sb.append(b.toChar()); tail = (tail shl 8) or b
            if (sb.length > 64 * 1024) throw CdpException("DevTools sent an oversized header")
        }
        return sb.toString()
    }

    private fun writeFrame(link: CdpForwarder.Link, opcode: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size < 65536 -> { out.write(0x80 or 126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
            else -> { out.write(0x80 or 127); for (s in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr s) and 0xff).toInt()) }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        out.write(mask)
        out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        link.output.write(out.toByteArray()); link.output.flush()
    }

    /** One whole message (continuation frames joined); answers pings so the connection stays alive. */
    private fun readMessage(link: CdpForwarder.Link): Pair<Int, ByteArray> {
        val acc = ByteArrayOutputStream(); var first = -1
        while (true) {
            val b0 = readByte(link.input); val b1 = readByte(link.input)
            val fin = b0 and 0x80 != 0; val op = b0 and 0x0f
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((readByte(link.input) shl 8) or readByte(link.input)).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte(link.input).toLong() } }
            if (len > 64L * 1024 * 1024) throw CdpException("DevTools sent a message that is too large")
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { readByte(link.input).toByte() } else null
            val data = ByteArray(len.toInt())
            var got = 0
            while (got < data.size) { val n = link.input.read(data, got, data.size - got); if (n < 0) throw CdpException("DevTools closed the connection"); got += n }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            if (op == 9) { writeFrame(link, 10, data); continue }
            if (op == 10) continue
            if (op == 8) return 8 to data
            if (first < 0) first = op
            acc.write(data)
            if (fin) return first to acc.toByteArray()
        }
    }

    private fun readByte(input: InputStream): Int = input.read().also { if (it < 0) throw CdpException("DevTools closed the connection") }
}
