package com.alpdroid.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors

/**
 * The "no login on this phone" half of the opencode web server. opencode (v2) has no switch to turn its password
 * off, so this small TCP front door listens where the browser goes (port 4096) and forwards to the real server on a
 * private loopback port, adding the server's login (`Authorization: Basic …`) to requests that come from this phone:
 * the browser on the phone opens `http://127.0.0.1:4096` straight into opencode, no password page.
 *
 * Who is let in without the password:
 *  - only connections that arrive from a loopback address (this phone). A connection from another device on the
 *    Wi-Fi (when [bindAll] is on) is piped through untouched, so the server asks it for the password as usual.
 *  - and only when the request is a same-origin one: its `Host` is 127.0.0.1/localhost:[listenPort] and its `Origin`,
 *    if it has one, is the same. A web page open in the phone's browser that tries to reach the server from another
 *    origin, or through a rebound DNS name, gets no login and therefore a 401.
 * Native apps on the phone that connect to 127.0.0.1 are not told apart from the browser — that is the price of "no
 * login", and why it is a setting.
 *
 * Each proxied request carries `Connection: close` upstream so the next request on a kept-alive browser connection
 * cannot slip past the header injection; a WebSocket upgrade keeps its own connection and is then piped as-is.
 */
class LocalAuthProxy(
    private val listenPort: Int,
    private val upstreamPort: Int,
    private val bindAll: Boolean,
    user: String,
    password: String,
) {
    private val authHeader = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "oc-proxy").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    /** The port actually bound (differs from [listenPort] only when that was 0, as in tests). */
    val boundPort: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (server != null) return
        val address = InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1")
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(address, listenPort), 64)
        server = s
        pool.execute {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (_: Exception) { }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            upstream = Socket().apply { connect(InetSocketAddress("127.0.0.1", upstreamPort), 3000) }
            val fromPhone = (client.remoteSocketAddress as? InetSocketAddress)?.address?.isLoopbackAddress == true
            if (fromPhone) {
                val head = readHead(client.getInputStream()) ?: return
                upstream.getOutputStream().write(rewriteHead(head, bound()))
            }
            val up = upstream
            // When the server's answer ends (it closes after each request), close both sides so the loop below,
            // blocked reading the browser, ends too instead of waiting for the browser to hang up.
            pool.execute {
                pipe(up.getInputStream(), client)
                runCatching { client.close() }
                runCatching { up.close() }
            }
            pipe(client.getInputStream(), up)
        } catch (_: Exception) {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun bound(): Int = if (listenPort == 0) boundPort else listenPort

    /** Copies [from] to [to] until either side ends, then shuts the destination's output so the peer sees the end. */
    private fun pipe(from: InputStream, to: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.getOutputStream().write(buf, 0, n)
                to.getOutputStream().flush()
            }
        } catch (_: Exception) {
        }
        runCatching { to.shutdownOutput() }
    }

    /** Rewrites a request head (everything up to and including the blank line, plus any body bytes read with it). */
    internal fun rewriteHead(head: ByteArray, port: Int): ByteArray {
        val end = indexOfBlankLine(head)
        val text = String(head, 0, end, Charsets.ISO_8859_1)
        val rest = head.copyOfRange(end + 4, head.size)
        val lines = text.split("\r\n")
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val hostOk = headers["host"] in setOf("127.0.0.1:$port", "localhost:$port")
        val origin = headers["origin"]
        val originOk = origin == null || origin in setOf("http://127.0.0.1:$port", "http://localhost:$port")
        val upgrade = headers.containsKey("upgrade")
        val out = ArrayList<String>()
        out += lines[0]
        for (line in lines.drop(1)) {
            val name = line.substringBefore(':').trim().lowercase()
            if (name == "authorization" || name == "connection") continue
            out += line
        }
        if (hostOk && originOk) out += "Authorization: $authHeader"
        out += if (upgrade) "Connection: Upgrade" else "Connection: close"
        return (out.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1) + rest
    }

    private fun indexOfBlankLine(b: ByteArray): Int {
        for (i in 0..b.size - 4) if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
        return -1
    }

    /** Reads until the end of the request head; null on EOF or an absurdly large head. Keeps any bytes past it. */
    private fun readHead(input: InputStream): ByteArray? {
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (acc.size() < 64 * 1024) {
            val n = input.read(buf)
            if (n < 0) return null
            acc.write(buf, 0, n)
            val bytes = acc.toByteArray()
            if (indexOfBlankLine(bytes) >= 0) return bytes
        }
        return null
    }
}
