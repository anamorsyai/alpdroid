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
}
