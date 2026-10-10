package com.alpdroid.proxy

import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
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

/** Exercises the live intercept hook on the plain-HTTP path (the HTTPS path shares the same decision code). */
class ProxyInterceptorTest {
    private lateinit var origin: HttpServer
    private lateinit var engineScope: CoroutineScope
    private var lastRequestBody: String? = null
    private var lastRequestPath: String? = null

    @BeforeTest
    fun setUp() {
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/echo") { exchange ->
                lastRequestPath = exchange.requestURI.toString()
                lastRequestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val body = "origin-said-$lastRequestBody".toByteArray()
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

    private fun engine(store: TransactionStore, interceptor: ProxyInterceptor) = ProxyEngine(
        port = 0, source = TrafficSource.BROWSER, store = store, scope = ScopeFilter(), respectScope = false,
        interceptor = interceptor,
    ).also { it.start(engineScope); Thread.sleep(100) }

    private fun post(proxyPort: Int, path: String, body: String): String {
        Socket("127.0.0.1", proxyPort).use { s ->
            val bytes = body.toByteArray()
            val out = s.getOutputStream()
            out.write("POST http://127.0.0.1:${origin.address.port}$path HTTP/1.1\r\n".toByteArray())
            out.write("Host: 127.0.0.1:${origin.address.port}\r\n".toByteArray())
            out.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
            s.soTimeout = 5_000
            return BufferedInputStream(s.getInputStream()).readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `a request body edit reaches the origin with a corrected Content-Length`() {
        val store = TransactionStore()
        val e = engine(store, object : ProxyInterceptor {
            override suspend fun onRequest(req: InterceptedRequest) = RequestAction.Replace(body = "EDITED".toByteArray())
            override suspend fun onResponse(res: InterceptedResponse) = ResponseAction.Forward
        })
        val resp = post(e.boundPort, "/echo", "original")
        Thread.sleep(100)
        assertEquals("EDITED", lastRequestBody) // the origin saw the edited body, intact (right Content-Length)
        assertTrue(resp.contains("origin-said-EDITED"))
        e.stop()
    }

    @Test
    fun `a response can be replaced before it reaches the client`() {
        val store = TransactionStore()
        val e = engine(store, object : ProxyInterceptor {
            override suspend fun onRequest(req: InterceptedRequest) = RequestAction.Forward
            override suspend fun onResponse(res: InterceptedResponse) =
                ResponseAction.Replace(status = 403, body = "blocked".toByteArray())
        })
        val resp = post(e.boundPort, "/echo", "x")
        Thread.sleep(100)
        assertTrue(resp.startsWith("HTTP/1.1 403"), resp)
        assertTrue(resp.endsWith("blocked"))
        assertEquals(403, store.transactions.value.single().responseStatus)
        e.stop()
    }

    @Test
    fun `a dropped request is never forwarded and is recorded as dropped`() {
        val store = TransactionStore()
        lastRequestBody = null
        val e = engine(store, object : ProxyInterceptor {
            override suspend fun onRequest(req: InterceptedRequest) = RequestAction.Drop
            override suspend fun onResponse(res: InterceptedResponse) = ResponseAction.Forward
        })
        runCatching { post(e.boundPort, "/echo", "y") } // the client connection is closed with no response
        Thread.sleep(100)
        assertNull(lastRequestBody) // origin never saw it
        val tx = store.transactions.value.single()
        assertEquals(0, tx.responseStatus) // the "dropped" marker
        e.stop()
    }

    @Test
    fun `the request and its recorded transaction and response share one id`() {
        val store = TransactionStore()
        var seenRequestId: String? = null
        var seenResponseId: String? = null
        val e = engine(store, object : ProxyInterceptor {
            override suspend fun onRequest(req: InterceptedRequest): RequestAction { seenRequestId = req.id; return RequestAction.Forward }
            override suspend fun onResponse(res: InterceptedResponse): ResponseAction { seenResponseId = res.id; return ResponseAction.Forward }
        })
        post(e.boundPort, "/echo", "z")
        Thread.sleep(100)
        assertEquals(seenRequestId, seenResponseId)
        assertEquals(seenRequestId, store.transactions.value.single().id)
        e.stop()
    }
}
