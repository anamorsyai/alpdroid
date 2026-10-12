package com.alpdroid.app

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAuthProxyTest {
    private val login = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
    private val servers = ArrayList<ServerSocket>()
    private var proxy: LocalAuthProxy? = null

    @After fun tearDown() {
        proxy?.stop()
        servers.forEach { runCatching { it.close() } }
    }

    /** An upstream that records each request head it receives and answers "ok". */
    private fun upstream(heads: LinkedBlockingQueue<List<String>>): ServerSocket {
        val s = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                try {
                    s.accept().use { c ->
                        val r = c.getInputStream().bufferedReader()
                        heads.put(generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList())
                        c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    }
                } catch (_: Exception) { }
            }
        }
        return s
    }

    private fun startProxy(upPort: Int): LocalAuthProxy =
        LocalAuthProxy(0, upPort, bindAll = false, user = "opencode", password = "secret").also { it.start(); proxy = it }

    private fun request(port: Int, vararg headers: String): String {
        Socket("127.0.0.1", port).use { c ->
            c.soTimeout = 5000
            c.getOutputStream().write(("GET /api/session HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray())
            return c.getInputStream().bufferedReader().readText()
        }
    }

    @Test fun aRequestFromThePhoneIsSignedInAndTheAnswerComesBack() {
        val heads = LinkedBlockingQueue<List<String>>()
        val up = upstream(heads)
        val p = startProxy(up.localPort)
        val answer = request(p.boundPort, "Host: 127.0.0.1:${p.boundPort}", "Connection: keep-alive")
        assertTrue(answer, answer.startsWith("HTTP/1.1 200") && answer.endsWith("ok"))
        val head = heads.poll(2, TimeUnit.SECONDS)!!
        assertTrue(head.toString(), head.contains("Authorization: $login"))
        assertTrue(head.contains("Connection: close"))
        assertFalse(head.any { it.equals("Connection: keep-alive", ignoreCase = true) })
    }

    @Test fun aLoginTheBrowserSentItselfIsReplaced() {
        val heads = LinkedBlockingQueue<List<String>>()
        val p = startProxy(upstream(heads).localPort)
        request(p.boundPort, "Host: localhost:${p.boundPort}", "Authorization: Basic d3Jvbmc6d3Jvbmc=")
        val head = heads.poll(2, TimeUnit.SECONDS)!!
        assertEquals(1, head.count { it.startsWith("Authorization:", ignoreCase = true) })
        assertTrue(head.contains("Authorization: $login"))
    }

    @Test fun anotherOriginOrHostGetsNoLogin() {
        val heads = LinkedBlockingQueue<List<String>>()
        val p = startProxy(upstream(heads).localPort)
        request(p.boundPort, "Host: 127.0.0.1:${p.boundPort}", "Origin: http://evil.example")
        assertFalse(heads.poll(2, TimeUnit.SECONDS)!!.any { it.startsWith("Authorization:", ignoreCase = true) })
        request(p.boundPort, "Host: evil.example")
        assertFalse(heads.poll(2, TimeUnit.SECONDS)!!.any { it.startsWith("Authorization:", ignoreCase = true) })
        request(p.boundPort, "Host: 127.0.0.1:${p.boundPort}", "Origin: http://127.0.0.1:${p.boundPort}")
        assertTrue(heads.poll(2, TimeUnit.SECONDS)!!.contains("Authorization: $login"))
    }

    @Test fun aWebSocketUpgradeKeepsItsConnectionHeader() {
        val proxy = LocalAuthProxy(4096, 4097, false, "opencode", "secret")
        val head = ("GET /ws HTTP/1.1\r\nHost: 127.0.0.1:4096\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\nBODY").toByteArray()
        val out = String(proxy.rewriteHead(head, 4096), Charsets.ISO_8859_1)
        assertTrue(out, out.contains("Connection: Upgrade") && out.contains("Authorization: $login") && out.endsWith("BODY"))
        assertFalse(out.contains("Connection: close"))
    }

    @Test fun aRequestMarkedCrossSiteByTheBrowserGetsNoLogin() {
        val proxy = LocalAuthProxy(4096, 4097, false, "opencode", "secret")
        fun auth(vararg extra: String): Boolean {
            val head = ("GET /api/session HTTP/1.1\r\nHost: 127.0.0.1:4096\r\n" + extra.joinToString("") { "$it\r\n" } + "\r\n").toByteArray()
            return String(proxy.rewriteHead(head, 4096), Charsets.ISO_8859_1).contains("Authorization: ")
        }
        assertTrue(auth())                                  // no hint from the client: same-origin by Host/Origin
        assertTrue(auth("Sec-Fetch-Site: same-origin"))
        assertTrue(auth("Sec-Fetch-Site: none"))            // typed in the address bar / opened from the app
        assertFalse(auth("Sec-Fetch-Site: cross-site"))     // an <img> or navigation from another website
        assertFalse(auth("Sec-Fetch-Site: same-site"))
    }

    @Test fun aRequestRepeatingASecurityRelevantHeaderGetsNoLogin() {
        // The proxy would see one value while the server may route on the other: ambiguous, so no login.
        val proxy = LocalAuthProxy(4096, 4097, false, "opencode", "secret")
        fun auth(vararg lines: String): Boolean {
            val head = ("GET /api/session HTTP/1.1\r\n" + lines.joinToString("") { "$it\r\n" } + "\r\n").toByteArray()
            return String(proxy.rewriteHead(head, 4096), Charsets.ISO_8859_1).contains("Authorization: ")
        }
        assertTrue(auth("Host: 127.0.0.1:4096"))
        assertFalse(auth("Host: evil.example", "Host: 127.0.0.1:4096"))
        assertFalse(auth("Host: 127.0.0.1:4096", "Host: evil.example"))
        assertFalse(auth("Host: 127.0.0.1:4096", "Origin: http://127.0.0.1:4096", "Origin: http://evil.example"))
    }

    @Test fun aClientThatSendsNothingIsDroppedAndTheProxyKeepsServing() {
        val heads = LinkedBlockingQueue<List<String>>()
        val p = startProxy(upstream(heads).localPort)
        // Hold one connection open without a request head: it must not stop a normal request being served.
        Socket("127.0.0.1", p.boundPort).use { _ ->
            val answer = request(p.boundPort, "Host: 127.0.0.1:${p.boundPort}")
            assertTrue(answer, answer.startsWith("HTTP/1.1 200"))
        }
    }

    @Test fun stoppingTheProxyClosesItsOpenConnections() {
        val heads = LinkedBlockingQueue<List<String>>()
        val p = startProxy(upstream(heads).localPort)
        val idle = Socket("127.0.0.1", p.boundPort).apply { soTimeout = 3000 }
        Thread.sleep(600) // let the accept loop register the idle connection, even on a busy machine
        p.stop()
        // The proxy closed its side: reading ends (EOF) instead of waiting for a request that will never come.
        assertEquals(-1, idle.getInputStream().read())
        idle.close()
    }

    @Test fun streamsThatTheBrowserAbandonedDoNotUseUpTheFrontDoor() {
        // An event stream: the server sends its head and then goes quiet. Browsers open and drop these all the time (every page
        // load, every tab). Left behind, they used to pile up until the proxy could take no more.
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { servers += it }
        val held = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        thread(isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val c = s.accept(); held += c
                    thread(isDaemon = true) {
                        runCatching {
                            val r = c.getInputStream().bufferedReader()
                            val head = generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                            val ok = head.firstOrNull()?.contains("/api/last") == true
                            c.getOutputStream().write(
                                (if (ok) "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" else "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n").toByteArray())
                            c.getOutputStream().flush()
                            if (ok) c.close() else Thread.sleep(60_000) // quiet stream
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        val p = startProxy(s.localPort)
        repeat(300) {
            Socket("127.0.0.1", p.boundPort).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("GET /event HTTP/1.1\r\nHost: 127.0.0.1:${p.boundPort}\r\n\r\n".toByteArray())
                c.getOutputStream().flush()
                val r = c.getInputStream().bufferedReader()
                assertTrue(r.readLine().startsWith("HTTP/1.1 200")) // the head arrived, then the browser walks away
            }
        }
        // The proxy must not keep a parked thread per abandoned stream (they used to pile up, two per connection, with nothing to limit them).
        Thread.sleep(1500)
        val parked = Thread.getAllStackTraces().keys.count { it.name == "oc-proxy" && it.isAlive }
        assertTrue("$parked proxy threads are still parked on abandoned streams", parked < 40)
        // 300 abandoned streams later (far more than the 128 the proxy allows at once), a new request still gets through.
        var answer = ""
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !answer.endsWith("ok")) {
            answer = runCatching {
                Socket("127.0.0.1", p.boundPort).use { c ->
                    c.soTimeout = 3000
                    c.getOutputStream().write("GET /api/last HTTP/1.1\r\nHost: 127.0.0.1:${p.boundPort}\r\n\r\n".toByteArray())
                    c.getInputStream().bufferedReader().readText()
                }
            }.getOrDefault("")
            if (!answer.endsWith("ok")) Thread.sleep(200)
        }
        assertTrue("the front door must still answer: '$answer'", answer.endsWith("ok"))
        held.forEach { runCatching { it.close() } }
    }
}
