package com.alpdroid.proxy

import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyEngineTest {

    private lateinit var origin: HttpServer
    private lateinit var engineScope: CoroutineScope

    @BeforeTest
    fun setUp() {
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hello") { exchange ->
                val body = "hello from origin".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        origin.stop(0)
        engineScope.cancel()
    }

    @Test
    fun `plain HTTP request is forwarded and recorded in the History store`() {
        val store = TransactionStore()
        val engine = ProxyEngine(
            port = 0,
            source = TrafficSource.BROWSER,
            store = store,
            scope = ScopeFilter(),
            respectScope = false,
        )
        engine.start(engineScope)
        Thread.sleep(100) // let the accept loop bind

        val response = sendThroughProxy(engine.boundPort, origin.address.port, "/hello")
        assertTrue(response.contains("200"))
        assertTrue(response.contains("hello from origin"))

        Thread.sleep(100) // let the record() land on the store
        val recorded = store.transactions.value.single()
        assertEquals("GET", recorded.method)
        assertEquals("/hello", recorded.path)
        assertEquals(200, recorded.responseStatus)
        assertEquals("hello from origin", recorded.responseBody?.toString(Charsets.UTF_8))

        engine.stop()
    }

    @Test
    fun `out-of-scope host is not recorded when respectScope is true`() {
        val store = TransactionStore()
        val scope = ScopeFilter().apply { setPatterns(listOf("some-other-host.test")) }
        val engine = ProxyEngine(
            port = 0,
            source = TrafficSource.TERMINAL,
            store = store,
            scope = scope,
            respectScope = true,
        )
        engine.start(engineScope)
        Thread.sleep(100)

        sendThroughProxy(engine.boundPort, origin.address.port, "/hello")
        Thread.sleep(100)

        assertEquals(emptyList(), store.transactions.value)
        engine.stop()
    }

    /** Speaks the proxy protocol directly: absolute-URI request line, then reads the raw response. */
    private fun sendThroughProxy(proxyPort: Int, originPort: Int, path: String): String {
        Socket("127.0.0.1", proxyPort).use { socket ->
            val out: OutputStream = socket.getOutputStream()
            out.write("GET http://127.0.0.1:$originPort$path HTTP/1.1\r\n".toByteArray())
            out.write("Host: 127.0.0.1:$originPort\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.flush()
            socket.soTimeout = 5_000
            return BufferedInputStream(socket.getInputStream()).readBytes().toString(Charsets.UTF_8)
        }
    }
}
