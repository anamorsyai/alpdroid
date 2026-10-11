package com.alpdroid.proxy

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLSocket

/**
 * A minimal forward proxy: one engine instance per traffic source (browser,
 * terminal), each on its own port, so History can tag where a request came
 * from without any cooperation from the client beyond "use this proxy".
 *
 * HTTPS is intercepted when [tlsMitm] is present: the CONNECT is answered, the
 * client-facing side is terminated with a per-host leaf certificate issued by our
 * root CA, and the decrypted traffic is captured exactly like plain HTTP. Clients
 * only trust that interception because they trust the CA (WebView via its network
 * security config, Alpine tools via the CA in their bundle). When [tlsMitm] is null
 * — or the MITM handshake fails for any reason — a CONNECT falls back to tunnelling
 * byte-for-byte, logged as an opaque entry (host:port only).
 *
 * Bodies are buffered (so they can be intercepted/edited and captured): Content-Length, chunked
 * (de-chunked and re-framed with a Content-Length) and connection-close framing are all handled,
 * bounded by a size cap so a huge or hostile body cannot exhaust memory.
 */
class ProxyEngine(
    private val port: Int,
    private val source: TrafficSource,
    private val store: TransactionStore,
    private val scope: ScopeFilter,
    private val respectScope: Boolean,
    private val maxCapturedBodyBytes: Int = 64 * 1024,
    /**
     * Provider (not a value): the CA is generated on first use, and RSA keygen must never
     * run on the main thread — engines are constructed in Application.onCreate, but this
     * lambda only runs on Dispatchers.IO when the first CONNECT arrives.
     */
    private val tlsMitmProvider: () -> TlsMitm? = { null },
    /**
     * Live breakpoint hook (see [ProxyInterceptor]): when set, every decrypted request/response passes through it
     * before being forwarded, so a controller can pause, modify or drop it. Null (the default) means the relay is
     * byte-for-byte unchanged — exactly how it behaved before interception existed.
     */
    private val interceptor: ProxyInterceptor? = null,
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val activeConnections = AtomicInteger(0)

    val boundPort: Int get() = serverSocket?.localPort ?: port

    /**
     * Returns immediately — binding the socket is itself blocking I/O, so it (and the accept
     * loop) runs entirely on [Dispatchers.IO], not on whatever thread calls [start]. This
     * matters because [start] gets called straight from [android.app.Application.onCreate] on
     * the main thread for two engine instances; a blocking bind() there was a real (if small)
     * main-thread I/O violation on every app launch.
     */
    fun start(coroutineScope: CoroutineScope) {
        job = coroutineScope.launch(Dispatchers.IO) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = server
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                    activeConnections.decrementAndGet()
                    client.closeQuietly()
                    continue
                }
                // A throwable escaping a child would cancel this whole accept loop (structured concurrency)
                // and silently kill the proxy, so every failure of one connection is contained to it.
                launch(Dispatchers.IO) {
                    try {
                        client.soTimeout = IDLE_TIMEOUT_MS
                        handleClient(client)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Dropped, malformed, timed out — nothing to recover; the next connection is unaffected.
                    } finally {
                        activeConnections.decrementAndGet()
                        client.closeQuietly()
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        serverSocket?.closeQuietly()
        serverSocket = null
    }

    private suspend fun handleClient(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input)?.let(::parseRequestLine) ?: return
        val headers = readHeaders(input)
        val target = parseTarget(requestLine.method, requestLine.target) ?: return

        if (requestLine.method.equals("CONNECT", ignoreCase = true)) {
            handleConnectTunnel(client, target)
        } else {
            handleHttp(client, input, requestLine, headers, target)
        }
    }

    private suspend fun handleConnectTunnel(client: Socket, target: Target) {
        // Record the tunnel either way: even a fully-intercepted session was a CONNECT first,
        // and a failed MITM still leaves this as the only trace (host:port, no content).
        recordIfInScope(
            HttpTransaction(
                id = UUID.randomUUID().toString(),
                source = source,
                timestamp = Instant.now(),
                method = "CONNECT",
                scheme = "https",
                host = target.host,
                port = target.port,
                path = "",
                opaque = true,
            ),
            target.host,
        )

        val mitm = runCatching { tlsMitmProvider() }.getOrNull()
        if (mitm != null) {
            // A failed handshake leaves the socket unusable for an opaque tunnel, so it is simply closed.
            runCatching { handleMitmTunnel(client, target, mitm) }
                .onFailure { if (it is CancellationException) throw it }
            return
        }

        val upstream = try {
            Socket().apply { connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS) }
        } catch (e: IOException) {
            sendError(client.getOutputStream(), 502, "Bad Gateway", "Could not reach ${target.host}:${target.port}")
            return
        }
        client.soTimeout = 0 // an opaque tunnel may legitimately sit idle (websocket, long poll)
        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        upstream.use {
            bridge(client.getInputStream(), client.getOutputStream(), upstream.getInputStream(), upstream.getOutputStream()) {
                upstream.closeQuietly(); client.closeQuietly()
            }
        }
    }

    /** Copies both directions until either ends, then tears both sockets down so the other blocking read returns. */
    private suspend fun bridge(
        clientIn: InputStream, clientOut: OutputStream, upIn: InputStream, upOut: OutputStream, closeAll: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        val toUpstream = launch { pump(clientIn, upOut) }
        val toClient = launch { pump(upIn, clientOut) }
        val tearDown: (Throwable?) -> Unit = { closeAll() }
        toUpstream.invokeOnCompletion(tearDown)
        toClient.invokeOnCompletion(tearDown)
        toUpstream.join()
        toClient.join()
    }

    /**
     * Terminates the client's TLS with our leaf certificate, opens a real TLS session
     * upstream, and relays the decrypted HTTP with full capture. After CONNECT the client
     * speaks origin-form (`GET /path`), not proxy absolute-form — the target comes from
     * the CONNECT itself, not the request line.
     */
    private suspend fun handleMitmTunnel(client: Socket, target: Target, mitm: TlsMitm) = withContext(Dispatchers.IO) {
        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        client.getOutputStream().flush()
        // Create + handshake inside the try so a throw anywhere (an untrusted/rejected upstream cert, a
        // hostname-verification failure, a handshake error) closes BOTH sockets.
        val tlsClient = mitm.wrapClient(client, target.host)
        var upstream: SSLSocket? = null
        try {
            tlsClient.startHandshake()
            upstream = mitm.upstreamSocket(target.host, target.port)
            upstream.soTimeout = IDLE_TIMEOUT_MS
            upstream.startHandshake()
            val clientInput = BufferedInputStream(tlsClient.inputStream)
            val requestLine = readLine(clientInput)?.let(::parseRequestLine) ?: return@withContext
            val requestHeaders = readHeaders(clientInput)
            val up = upstream
            exchange(
                clientInput, tlsClient.outputStream, up.inputStream, up.outputStream,
                requestLine, requestHeaders, "https", target, requestLine.target.ifEmpty { "/" },
                setIdle = { ms -> tlsClient.soTimeout = ms; up.soTimeout = ms },
                closeAll = { up.closeQuietly(); tlsClient.closeQuietly() },
            )
        } finally {
            upstream?.closeQuietly()
            tlsClient.closeQuietly()
        }
    }

    private suspend fun handleHttp(
        client: Socket,
        clientInput: InputStream,
        requestLine: RequestLine,
        requestHeaders: List<Pair<String, String>>,
        target: Target,
    ) {
        val upstream = try {
            Socket().apply { connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS); soTimeout = IDLE_TIMEOUT_MS }
        } catch (e: IOException) {
            sendError(client.getOutputStream(), 502, "Bad Gateway", "Could not reach ${target.host}:${target.port}")
            return
        }
        upstream.use {
            exchange(
                clientInput, client.getOutputStream(), BufferedInputStream(upstream.getInputStream()), upstream.getOutputStream(),
                requestLine, requestHeaders, target.scheme, target, target.path.ifEmpty { "/" },
                setIdle = { ms -> client.soTimeout = ms; upstream.soTimeout = ms },
                closeAll = { upstream.closeQuietly(); client.closeQuietly() },
            )
        }
    }

    /**
     * One request/response over an already-open upstream — the single relay used by both the plain-HTTP and the
     * decrypted-HTTPS paths, so framing, interception, capture and error handling cannot drift apart.
     */
    private suspend fun exchange(
        clientIn: InputStream, clientOut: OutputStream, upIn: InputStream, upOut: OutputStream,
        requestLine: RequestLine, requestHeaders: List<Pair<String, String>>,
        scheme: String, target: Target, requestTarget: String,
        setIdle: (Int) -> Unit, closeAll: () -> Unit,
    ) {
        val start = Instant.now()
        val wantsUpgrade = headerValue(requestHeaders, "Upgrade") != null &&
            headerValue(requestHeaders, "Connection")?.contains("upgrade", ignoreCase = true) == true

        // A client that sent Expect: 100-continue waits for this before sending the body — without it, it stalls.
        val expects = headerValue(requestHeaders, "Expect")?.contains("100-continue", ignoreCase = true) == true
        if (expects) { clientOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); clientOut.flush() }

        val requestBody = readMessageBody(clientIn, requestHeaders, BODY_BUFFER_CAP, allowEofBody = false)
        if (requestBody.truncated) {
            sendError(clientOut, 413, "Payload Too Large", "Request body is larger than the ${BODY_BUFFER_CAP / (1024 * 1024)} MiB the proxy can intercept")
            return
        }

        val txId = UUID.randomUUID().toString()
        val req = ReqEdit(
            requestLine.method, requestTarget,
            requestHeaders.filterNot { it.first.equals("Expect", ignoreCase = true) }, requestBody.bytes,
        )
        applyRequestIntercept(txId, scheme, target.host, target.port, req)
        if (req.drop) {
            recordDropped(txId, start, req.method, scheme, target.host, target.port, req.target, req.headers, req.body)
            return
        }

        writeRequestLine(upOut, req.method, req.target, requestLine.version)
        writeHeaders(upOut, forwardHeaders(req.headers, req.body, connectionClose = !wantsUpgrade))
        req.body?.let { upOut.write(it) }
        upOut.flush()

        val resp: RespEdit
        var tooLarge = false
        try {
            var statusLine = readLine(upIn)
            var statusCode = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
            // Interim 1xx responses (100 Continue, 103 Early Hints) are not the final answer: skip to the real one.
            while (statusCode != null && statusCode in 100..199 && statusCode != 101) {
                readHeaders(upIn)
                statusLine = readLine(upIn)
                statusCode = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
            }
            if (statusLine == null) {
                sendError(clientOut, 502, "Bad Gateway", "The server closed the connection without answering")
                return
            }
            val responseHeaders = readHeaders(upIn)

            if (statusCode == 101 && wantsUpgrade) {
                // Protocol switch (WebSocket): relay the 101 as-is and pipe bytes both ways, uncaptured.
                clientOut.write("$statusLine\r\n".toByteArray(Charsets.US_ASCII))
                writeHeaders(clientOut, responseHeaders)
                clientOut.flush()
                recordIfInScope(
                    HttpTransaction(
                        id = txId, source = source, timestamp = start, method = req.method, scheme = scheme,
                        host = target.host, port = target.port, path = req.target, requestHeaders = req.headers,
                        responseStatus = 101, responseHeaders = responseHeaders,
                        durationMs = Instant.now().toEpochMilli() - start.toEpochMilli(),
                    ),
                    target.host,
                )
                setIdle(0)
                bridge(clientIn, clientOut, upIn, upOut, closeAll)
                return
            }

            val body = if (responseHasNoBody(req.method, statusCode)) BodyResult(null, false)
            else readMessageBody(upIn, responseHeaders, BODY_BUFFER_CAP, allowEofBody = true)
            if (body.truncated) tooLarge = true
            resp = RespEdit(statusLine, statusCode, responseHeaders, body.bytes)
        } catch (e: IOException) {
            sendError(clientOut, 502, "Bad Gateway", "Upstream error: ${e.javaClass.simpleName}")
            return
        }

        if (tooLarge) {
            // Forwarding the first 25 MiB as a complete 200 would hand the client a silently corrupt download.
            sendError(clientOut, 502, "Bad Gateway", "Response is larger than the ${BODY_BUFFER_CAP / (1024 * 1024)} MiB the proxy can intercept")
            recordDropped(txId, start, req.method, scheme, target.host, target.port, req.target, req.headers, req.body)
            return
        }

        applyResponseIntercept(txId, target.host, target.port, resp)
        if (!resp.drop) {
            resp.statusLine?.let { clientOut.write("$it\r\n".toByteArray(Charsets.US_ASCII)) }
            writeHeaders(clientOut, forwardHeaders(resp.headers, resp.body, connectionClose = true))
            resp.body?.let { clientOut.write(it) }
            clientOut.flush()
        }

        recordIfInScope(
            HttpTransaction(
                id = txId,
                source = source,
                timestamp = start,
                method = req.method,
                scheme = scheme,
                host = target.host,
                port = target.port,
                path = req.target,
                requestHeaders = req.headers,
                requestBody = req.body?.take(maxCapturedBodyBytes),
                responseStatus = if (resp.drop) null else resp.status,
                responseHeaders = if (resp.drop) emptyList() else resp.headers,
                responseBody = if (resp.drop) null else resp.body?.take(maxCapturedBodyBytes),
                durationMs = Instant.now().toEpochMilli() - start.toEpochMilli(),
            ),
            target.host,
        )
    }

    private fun sendError(out: OutputStream, code: Int, reason: String, message: String) {
        runCatching {
            val body = message.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII),
            )
            out.write(body)
            out.flush()
        }
    }

    /** Mutable request fields carried through [applyRequestIntercept] so both relay paths share one decision site. */
    private class ReqEdit(var method: String, var target: String, var headers: List<Pair<String, String>>, var body: ByteArray?) {
        var drop = false
    }

    private class RespEdit(var statusLine: String?, var status: Int?, var headers: List<Pair<String, String>>, var body: ByteArray?) {
        var drop = false
    }

    private suspend fun applyRequestIntercept(id: String, scheme: String, host: String, port: Int, r: ReqEdit) {
        val icept = interceptor ?: return
        when (val a = icept.onRequest(InterceptedRequest(id, source, r.method, scheme, host, port, r.target, r.headers, r.body))) {
            RequestAction.Forward -> {}
            RequestAction.Drop -> r.drop = true
            is RequestAction.Replace -> {
                a.method?.let { r.method = it }
                a.target?.let { r.target = it }
                a.headers?.let { r.headers = it }
                if (a.body != null) { r.body = a.body; r.headers = withContentLength(r.headers, a.body.size) }
            }
        }
    }

    private suspend fun applyResponseIntercept(id: String, host: String, port: Int, r: RespEdit) {
        val icept = interceptor ?: return
        when (val a = icept.onResponse(InterceptedResponse(id, source, host, port, r.status, r.headers, r.body))) {
            ResponseAction.Forward -> {}
            ResponseAction.Drop -> r.drop = true
            is ResponseAction.Replace -> {
                a.status?.let { r.status = it; r.statusLine = replaceStatusCode(r.statusLine, it) }
                a.headers?.let { r.headers = it }
                if (a.body != null) { r.body = a.body; r.headers = withContentLength(r.headers, a.body.size) }
            }
        }
    }

    private fun recordIfInScope(tx: HttpTransaction, host: String) {
        if (!respectScope || scope.matches(host)) store.record(tx)
    }

    /** Records a request the interceptor dropped (never forwarded): no response, flagged by a 0 status we can show. */
    private fun recordDropped(
        id: String, start: Instant, method: String, scheme: String, host: String, port: Int,
        target: String, headers: List<Pair<String, String>>, body: ByteArray?,
    ) {
        recordIfInScope(
            HttpTransaction(
                id = id, source = source, timestamp = start, method = method, scheme = scheme, host = host, port = port,
                path = target, requestHeaders = headers, requestBody = body?.take(maxCapturedBodyBytes),
                responseStatus = 0, responseHeaders = emptyList(), responseBody = null,
                durationMs = Instant.now().toEpochMilli() - start.toEpochMilli(),
            ),
            host,
        )
    }

    /**
     * Headers to forward: strip hop-by-hop Connection/Proxy-Connection, and — because we always buffer and may
     * de-chunk or truncate the body — re-frame Content-Length to the bytes we will actually write and drop any
     * stale Transfer-Encoding (so the peer never waits on chunks that won't come, and never on a length that
     * doesn't match). A bodyless message keeps whatever framing it had.
     */
    private fun forwardHeaders(headers: List<Pair<String, String>>, body: ByteArray?, connectionClose: Boolean): List<Pair<String, String>> {
        // Connection is rewritten only when we close the connection ourselves; an Upgrade handshake keeps its own.
        var h = headers.filterNot {
            it.first.equals("Proxy-Connection", ignoreCase = true) ||
                (connectionClose && it.first.equals("Connection", ignoreCase = true))
        }
        if (body != null) h = withContentLength(h, body.size)
        return if (connectionClose) h + ("Connection" to "close") else h
    }

    private suspend fun pump(from: InputStream, to: OutputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        try {
            while (isActive) {
                val n = from.read(buffer)
                if (n == -1) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (e: IOException) {
            // Either side closed; end of tunnel.
        }
    }

    private fun ByteArray.take(max: Int): ByteArray = if (size <= max) this else copyOf(max)

    private fun Socket.closeQuietly() = try { close() } catch (e: IOException) { }
    private fun ServerSocket.closeQuietly() = try { close() } catch (e: IOException) { }

    private companion object {
        /** Largest body buffered in memory for forwarding/interception. A bigger body is truncated here rather
         *  than risking OOM — a hostile or simply large Content-Length could otherwise allocate gigabytes.
         *  History still keeps only maxCapturedBodyBytes of whatever is forwarded. */
        const val BODY_BUFFER_CAP = 25 * 1024 * 1024
        const val MAX_CONNECTIONS = 256
        const val CONNECT_TIMEOUT_MS = 10_000
        /** A peer that goes silent this long mid-message is dropped instead of pinning a thread and socket forever. */
        const val IDLE_TIMEOUT_MS = 60_000
    }
}
