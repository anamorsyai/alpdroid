package com.alpdroid.app

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpencodeWebTest {
    @Test fun quotesAreEscapedSoAPasswordCannotEndTheQuoting() {
        assertEquals("'abc'", OpencodeWeb.shQuote("abc"))
        assertEquals("'a'\\''b'", OpencodeWeb.shQuote("a'b"))
    }

    @Test fun commandBindsTheRequestedAddressAndPassesThePassword() {
        val lan = OpencodeWeb.command(4096, true, "Pw123")
        assertTrue(lan.contains("serve --hostname 0.0.0.0 --port 4096"))
        assertTrue(lan.contains("OPENCODE_SERVER_PASSWORD='Pw123'"))
        val local = OpencodeWeb.command(4096, false, "Pw123")
        assertTrue(local.contains("serve --hostname 127.0.0.1 --port 4096"))
        assertFalse(local.contains("0.0.0.0"))
    }

    @Test fun commandRestartsOnCrashesButNotOnCtrlC() {
        val cmd = OpencodeWeb.command(4096, true, "x")
        assertTrue(cmd.contains("trap 'exit 0' INT TERM"))
        assertTrue(cmd.contains("-eq 130"))   // Ctrl+C ends it
        assertTrue(cmd.contains("restarting in"))
        assertTrue(cmd.contains("giving up"))
    }

    /** A one-purpose HTTP server: 200 for the right Basic login, 401 otherwise. */
    private fun fakeServer(): ServerSocket {
        val good = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { c ->
                        val lines = c.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList() }
                        val ok = lines.any { it.equals("Authorization: $good", ignoreCase = true) }
                        c.getOutputStream().write("HTTP/1.1 ${if (ok) "200 OK" else "401 Unauthorized"}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } catch (_: Exception) { }
            }
        }
        return server
    }

    @Test fun probeTellsAWorkingLoginFromAWrongOne() {
        val server = fakeServer()
        try {
            val port = server.localPort
            assertTrue(OpencodeWeb.portOpen(port))
            assertEquals(200, OpencodeWeb.probeLogin(port, "secret"))
            assertEquals(401, OpencodeWeb.probeLogin(port, "wrong"))
        } finally {
            server.close()
        }
    }

    @Test fun nothingListeningIsReportedAsClosed() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(OpencodeWeb.portOpen(port))
        assertEquals(-1, OpencodeWeb.probeLogin(port, "x", 500))
    }

    @Test fun readyMessageSaysWhoCanReachIt() {
        val lan = OpencodeWeb.readyMessage(true, 4096, listOf("192.168.1.5"), "pw")
        assertTrue(lan.contains("http://192.168.1.5:4096"))
        assertTrue(lan.contains("Password: pw"))
        assertTrue(OpencodeWeb.readyMessage(false, 4096, listOf("192.168.1.5"), "pw").contains("Other devices cannot reach it"))
    }
}
