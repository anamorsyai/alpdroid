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
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
     * lambda only runs on connDispatcher when the first CONNECT arrives.
     */
    private val tlsMitmProvider: () -> TlsMitm? = { null },
    /**
     * Live breakpoint hook (see [ProxyInterceptor]): when set, every decrypted request/response passes through it
     * before being forwarded, so a controller can pause, modify or drop it. Null (the default) means the relay is
     * byte-for-byte unchanged — exactly how it behaved before interception existed.
     */
    private val interceptor: ProxyInterceptor? = null,
    /** Bytes this engine has actually moved, so the UI can show the real figure (see [ProxyStats]). */
    val stats: ProxyStats = ProxyStats(),
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    /** Guards [job]/[serverSocket]: start() and stop() used to race — a second start() launched a
     *  second accept loop (and a second bind), and a stop() during start's async bind left the
     *  socket assigned after stop() had cleared it, leaking a listening socket. */
    private val lifecycleLock = Any()
    private val activeConnections = AtomicInteger(0)

    val boundPort: Int get() = serverSocket?.localPort ?: port

    /**
     * Returns immediately — binding the socket is itself blocking I/O, so it (and the accept
     * loop) runs entirely on [connDispatcher], not on whatever thread calls [start]. This
     * matters because [start] gets called straight from [android.app.Application.onCreate] on
     * the main thread for two engine instances; a blocking bind() there was a real (if small)
     * main-thread I/O violation on every app launch.
     */
    fun start(coroutineScope: CoroutineScope) {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return // idempotent: a second start() is a no-op, not a second loop
            job = coroutineScope.launch(connDispatcher) {
                val server = ServerSocket()
                try {
                    server.reuseAddress = true
                    server.bind(InetSocketAddress("127.0.0.1", port))
                    synchronized(lifecycleLock) {
                        // stop() may have run while the (blocking) bind was in flight: do not
                        // publish a socket stop() already closed, and do not outlive our job.
                        if (job?.isActive != true || coroutineContext[Job]?.isActive != true) {
                            server.closeQuietly()
                            return@launch
                        }
                        serverSocket = server
                    }
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
                        launch(connDispatcher) {
                            try {
                                client.soTimeout = IDLE_TIMEOUT_MS
                                client.tcpNoDelay = true // we write whole messages at once; don't let Nagle hold the tail back
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
                } finally {
                    // The accept loop ending (bind failure, accept error, cancel) closes the
                    // socket here too — stop() is not the only way out any more.
                    server.closeQuietly()
                    synchronized(lifecycleLock) {
                        if (serverSocket === server) serverSocket = null
                        if (job === coroutineContext[Job]) job = null
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            val j = job
            job = null
            j?.cancel()
            serverSocket?.closeQuietly()
            serverSocket = null
        }
    }

    private suspend fun handleClient(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input)?.let(::parseRequestLine) ?: return
        val headers = readHeaders(input)
        // A malformed target used to close the connection silently; answer 400 so the
        // client (and the log) can tell a bad request from a dropped connection. No
        // localhost block here: local access stays open by design.
        val target = parseTarget(requestLine.method, requestLine.target)
            ?: run { sendError(client.getOutputStream(), 400, "Bad Request", "Invalid request target"); return }

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
        clientIn: InputStream, clientOut: OutputStream, upIn: InputStream, upOut: OutputStream,
        tapUp: ((ByteArray, Int, Int) -> Unit)? = null, tapDown: ((ByteArray, Int, Int) -> Unit)? = null,
        closeAll: () -> Unit,
    ) = withContext(connDispatcher) {
        val toUpstream = launch { pump(clientIn, upOut, stats.bytesUp, tapUp) }
        val toClient = launch { pump(upIn, clientOut, stats.bytesDown, tapDown) }
        val tearDown: (Throwable?) -> Unit = { closeAll() }
        toUpstream.invokeOnCompletion(tearDown)
        toClient.invokeOnCompletion(tearDown)
        toUpstream.join()
        toClient.join()
    }

    /**
     * Terminates the client's TLS with our leaf certificate and relays the decrypted HTTP with full capture. After
     * CONNECT the client speaks origin-form (`GET /path`), not proxy absolute-form — the target comes from the
     * CONNECT itself. The tunnel is kept open for several requests (HTTP keep-alive) and the upstream TLS session is
     * reused with it: a browser fetches dozens of subresources per page, and paying a CONNECT plus two TLS handshakes
     * for each one was the main reason intercepted browsing felt slow.
     */
    private suspend fun handleMitmTunnel(client: Socket, target: Target, mitm: TlsMitm) = withContext(connDispatcher) {
        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        client.getOutputStream().flush()
        // Create + handshake inside the try so a throw anywhere (a rejected client handshake, an untrusted upstream
        // certificate, a hostname mismatch) closes BOTH sockets.
        val tlsClient = mitm.wrapClient(client, target.host)
        val upstream = Upstream(IDLE_TIMEOUT_MS) { mitm.upstreamSocket(target.host, target.port).also { it.startHandshake() } }
        try {
            tlsClient.startHandshake()
            val clientInput = BufferedInputStream(tlsClient.inputStream)
            var served = 0
            while (served < MAX_REQUESTS_PER_TUNNEL) {
                // Between requests the client may sit idle; don't hold a thread for the full request timeout.
                tlsClient.soTimeout = if (served == 0) IDLE_TIMEOUT_MS else KEEPALIVE_IDLE_MS
                val line = try { readLine(clientInput) } catch (e: java.net.SocketTimeoutException) { null } ?: break
                val requestLine = parseRequestLine(line) ?: break
                val requestHeaders = readHeaders(clientInput)
                tlsClient.soTimeout = IDLE_TIMEOUT_MS
                served++
                val keepOpen = exchange(
                    clientInput, tlsClient.outputStream, tlsClient, requestLine, requestHeaders,
                    "https", target, requestLine.target.ifEmpty { "/" }, upstream, allowReuse = true,
                )
                if (!keepOpen) break
            }
        } finally {
            upstream.close()
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
        // An https absolute-form (`GET https://host/path`) has no upgrade path here: this
        // handler speaks plain HTTP upstream, so it would send cleartext to a TLS port.
        // HTTPS must arrive as CONNECT (then MITM or a tunnel); reject the absolute form
        // with 400 rather than guessing. The Upgrade path is the same story — a plain-HTTP
        // handler cannot upgrade to TLS — so it is covered by the same rejection.
        if (target.scheme.equals("https", ignoreCase = true)) {
            sendError(client.getOutputStream(), 400, "Bad Request", "HTTPS requests must use CONNECT, not absolute-form")
            return
        }
        val socket = try {
            Socket().apply { connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS) }
        } catch (e: IOException) {
            sendError(client.getOutputStream(), 502, "Bad Gateway", "Could not reach ${target.host}:${target.port}")
            return
        }
        val upstream = Upstream(IDLE_TIMEOUT_MS) { socket }
        try {
            exchange(
                clientInput, client.getOutputStream(), client, requestLine, requestHeaders,
                target.scheme, target, target.path.ifEmpty { "/" }, upstream, allowReuse = false,
            )
        } finally { upstream.close() }
    }

    /** The connection to the real server for one tunnel: opened on first use, reopened after it was closed. */
    private class Upstream(private val soTimeoutMs: Int, private val open: () -> Socket) {
        private var socket: Socket? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        /** True once a full exchange completed on this connection — a failure then may just be a stale idle socket. */
        var used = false

        val isOpen: Boolean get() = socket != null

        fun streams(): Pair<InputStream, OutputStream> {
            if (socket == null) {
                val s = open()
                s.soTimeout = soTimeoutMs
                runCatching { s.tcpNoDelay = true }
                socket = s; input = BufferedInputStream(s.getInputStream()); output = s.getOutputStream(); used = false
            }
            return input!! to output!!
        }

        fun setTimeout(ms: Int) { runCatching { socket?.soTimeout = ms } }

        fun close() {
            try { socket?.close() } catch (_: IOException) { }
            socket = null; input = null; output = null; used = false
        }
    }

    private class ResponseHead(val statusLine: String, val status: Int?, val headers: List<Pair<String, String>>)

    /** Reads the status line and headers of the final response, skipping interim 1xx answers; null if the peer closed. */
    private fun readResponseHead(upIn: InputStream): ResponseHead? {
        var statusLine = readLine(upIn) ?: return null
        var code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        while (code != null && code in 100..199 && code != 101) {
            readHeaders(upIn)
            statusLine = readLine(upIn) ?: return null
            code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        }
        return ResponseHead(statusLine, code, readHeaders(upIn))
    }

    /**
     * One request/response over [upstream] — the single relay used by both the plain-HTTP and the decrypted-HTTPS
     * paths, so framing, interception, capture and error handling cannot drift apart. Returns true when the client
     * connection may carry another request (only ever with [allowReuse]).
     */
    private suspend fun exchange(
        clientIn: InputStream, clientOut: OutputStream, clientSocket: Socket,
        requestLine: RequestLine, requestHeaders: List<Pair<String, String>>,
        scheme: String, target: Target, requestTarget: String,
        upstream: Upstream, allowReuse: Boolean,
    ): Boolean {
        val start = Instant.now()
        val connTokens = headerValue(requestHeaders, "Connection")?.lowercase().orEmpty()
        val wantsUpgrade = headerValue(requestHeaders, "Upgrade") != null && connTokens.contains("upgrade")
        val clientWantsClose = connTokens.contains("close") || (requestLine.version == "HTTP/1.0" && !connTokens.contains("keep-alive"))

        // A client that sent Expect: 100-continue waits for this before sending the body — without it, it stalls.
        val expects = headerValue(requestHeaders, "Expect")?.contains("100-continue", ignoreCase = true) == true
        if (expects) { clientOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); clientOut.flush() }

        val requestBody = readMessageBody(clientIn, requestHeaders, BODY_BUFFER_CAP, allowEofBody = false)
        if (requestBody.incomplete) return false // the client stopped sending mid-body: nothing sensible to forward
        if (requestBody.truncated) {
            sendError(clientOut, 413, "Payload Too Large", "Request body is larger than the ${BODY_BUFFER_CAP / (1024 * 1024)} MiB the proxy can intercept")
            return false
        }

        val txId = UUID.randomUUID().toString()
        val req = ReqEdit(
            requestLine.method, requestTarget,
            requestHeaders.filterNot { it.first.equals("Expect", ignoreCase = true) }, requestBody.bytes,
        )
        if (applyRequestIntercept(txId, scheme, target.host, target.port, req)) {
            // The interceptor threw or timed out (30s): answer 502 and record the drop
            // instead of hanging the client on a parked request, or forwarding uninspected.
            sendError(clientOut, 502, "Bad Gateway", "Interceptor failed or timed out")
            recordDropped(txId, start, req.method, scheme, target.host, target.port, req.target, req.headers, req.body)
            return false
        }
        if (req.drop) {
            recordDropped(txId, start, req.method, scheme, target.host, target.port, req.target, req.headers, req.body)
            return false
        }

        // Only gzip/deflate are asked for (we can decode those for capture and rules; br/zstd we could not).
        // A WebSocket upgrade is sent without the compression extension, so the frames that pass can be read; the server then
        // simply does not compress them (the extension is optional, the connection works the same).
        val outHeaders = if (wantsUpgrade) req.headers.filterNot { it.first.equals("Sec-WebSocket-Extensions", ignoreCase = true) } else narrowAcceptEncoding(req.headers)

        // Send, and read the head of the answer. A connection reused from an earlier exchange may have been closed by
        // the server while idle; that shows up as a failed write/read, and is retried once on a fresh connection.
        var head: ResponseHead? = null
        var attempt = 0
        while (head == null) {
            val reusing = upstream.isOpen && upstream.used
            try {
                val (upIn, upOut) = upstream.streams()
                // The head goes out as ONE write: a TLS record and TCP segment per header line is what made every
                // request wait on Nagle/delayed-ACK (tens of ms each).
                val reqBytes = java.io.ByteArrayOutputStream(1024)
                writeRequestLine(reqBytes, req.method, req.target, requestLine.version)
                writeHeaders(reqBytes, forwardHeaders(outHeaders, req.body, connectionClose = !allowReuse && !wantsUpgrade))
                req.body?.let { reqBytes.write(it) }
                upOut.write(reqBytes.toByteArray())
                upOut.flush()
                stats.bytesUp.addAndGet(reqBytes.size().toLong())
                head = readResponseHead(upIn) ?: throw IOException("connection closed")
            } catch (e: IOException) {
                upstream.close()
                // Retry a stale reused connection exactly once — but only for idempotent
                // methods. Retrying a POST/PUT after its bytes may already have reached the
                // server would execute the side effect twice; a safe method can be replayed.
                if (reusing && attempt++ == 0 && isIdempotent(req.method)) continue
                val timedOut = e is java.net.SocketTimeoutException
                sendError(clientOut, if (timedOut) 504 else 502, if (timedOut) "Gateway Timeout" else "Bad Gateway", "Upstream error: ${e.javaClass.simpleName}")
                return false
            }
        }
        val (upIn, upOut) = upstream.streams()

        if (head.status == 101 && wantsUpgrade) {
            // Protocol switch (WebSocket): relay the 101 as-is and pipe bytes both ways, uncaptured.
            val upgradeHead = java.io.ByteArrayOutputStream(512)
            upgradeHead.write("${head.statusLine}\r\n".toByteArray(Charsets.ISO_8859_1))
            writeHeaders(upgradeHead, head.headers)
            clientOut.write(upgradeHead.toByteArray())
            clientOut.flush()
            recordIfInScope(
                HttpTransaction(
                    id = txId, source = source, timestamp = start, method = req.method, scheme = scheme,
                    host = target.host, port = target.port, path = req.target, requestHeaders = req.headers,
                    responseStatus = 101, responseHeaders = head.headers,
                    durationMs = Instant.now().toEpochMilli() - start.toEpochMilli(),
                ),
                target.host,
            )
            runCatching { clientSocket.soTimeout = 0 }
            upstream.setTimeout(0)
            // Record the frames in both directions (WebSocket only; anything else that upgrades is just relayed).
            val isWebSocket = headerValue(requestHeaders, "Upgrade")?.equals("websocket", ignoreCase = true) == true
            val frames = if (isWebSocket && (!respectScope || scope.matches(target.host))) store.webSockets.open(txId, "websocket") else null
            val up = frames?.let { f -> WsFrameParser(true) { f.add(true, it.opcode, it.fin, it.length, it.payload, it.truncated) } }
            val down = frames?.let { f -> WsFrameParser(false) { f.add(false, it.opcode, it.fin, it.length, it.payload, it.truncated) } }
            bridge(clientIn, clientOut, upIn, upOut, up?.let { p -> { b, o, l -> p.feed(b, o, l) } }, down?.let { p -> { b, o, l -> p.feed(b, o, l) } }) { upstream.close(); try { clientSocket.close() } catch (_: IOException) { } }
            return false
        }

        // A Server-Sent Events stream never ends, so it cannot be buffered like an ordinary answer (the page would never see an
        // event): it is passed on as it arrives, and each event is kept in History. Not paused or edited by the interceptor.
        if (head.status in 200..299 && !responseHasNoBody(req.method, head.status) &&
            headerValue(head.headers, "Content-Type")?.lowercase()?.startsWith("text/event-stream") == true) {
            streamEvents(clientSocket, clientOut, upstream, upIn, head, txId, start, req, scheme, target)
            return false
        }

        val noBody = responseHasNoBody(req.method, head.status)
        val body = try {
            if (noBody) BodyResult(null, false) else readMessageBody(upIn, head.headers, BODY_BUFFER_CAP, allowEofBody = true)
        } catch (e: IOException) {
            upstream.close()
            sendError(clientOut, if (e is java.net.SocketTimeoutException) 504 else 502, "Bad Gateway", "Upstream error: ${e.javaClass.simpleName}")
            return false
        }

        if (body.incomplete) {
            // The origin closed before the length it announced (or sent broken chunking): passing the part on as a finished
            // response would hand the client a silently short download.
            upstream.close()
            sendError(clientOut, 502, "Bad Gateway", "Upstream closed before the whole response arrived")
            return false
        }

        // Is the upstream connection still good for the next request? Not when the body ran to EOF, or the server said close.
        val respTokens = headerValue(head.headers, "Connection")?.lowercase().orEmpty()
        val eofDelimited = !noBody && headerValue(head.headers, "Content-Length") == null &&
            headerValue(head.headers, "Transfer-Encoding")?.contains("chunked", ignoreCase = true) != true
        val upstreamReusable = allowReuse && !eofDelimited && !respTokens.contains("close") && !head.statusLine.startsWith("HTTP/1.0")
        if (upstreamReusable) upstream.used = true else upstream.close()

        if (body.truncated) {
            // Forwarding the first 25 MiB as a complete 200 would hand the client a silently corrupt download.
            upstream.close()
            sendError(clientOut, 502, "Bad Gateway", "Response is larger than the ${BODY_BUFFER_CAP / (1024 * 1024)} MiB the proxy can intercept")
            recordDropped(txId, start, req.method, scheme, target.host, target.port, req.target, req.headers, req.body)
            return false
        }

        val (plainHeaders, plainBody) = decodeContentEncoding(head.headers, body.bytes, BODY_BUFFER_CAP)
        val resp = RespEdit(head.statusLine, head.status, plainHeaders, plainBody)
        if (applyResponseIntercept(txId, target.host, target.port, resp)) {
            sendError(clientOut, 502, "Bad Gateway", "Interceptor failed or timed out")
            recordDropped(txId, start, req.method, scheme, target.host, target.port, req.target, req.headers, req.body)
            return false
        }
        if (resp.drop) {
            recordIfInScope(
                HttpTransaction(
                    id = txId, source = source, timestamp = start, method = req.method, scheme = scheme,
                    host = target.host, port = target.port, path = req.target, requestHeaders = req.headers,
                    requestBody = req.body?.take(maxCapturedBodyBytes),
                    durationMs = Instant.now().toEpochMilli() - start.toEpochMilli(),
                ),
                target.host,
            )
            return false
        }

        // Nobody touched a compressed answer: send the original compressed bytes (smaller, and byte-for-byte what the
        // server produced) instead of the decoded copy kept for capture.
        val untouched = plainHeaders !== head.headers && resp.body === plainBody && resp.headers === plainHeaders && resp.status == head.status
        val outBody = if (untouched) body.bytes else resp.body
        var outRespHeaders = if (untouched) head.headers else resp.headers
        // A body that ended at EOF (or was empty) has no length on the wire; give the client one so it can keep going.
        if (outBody == null && !noBody && headerValue(outRespHeaders, "Content-Length") == null) outRespHeaders = withContentLength(outRespHeaders, 0)

        val keepClient = allowReuse && !clientWantsClose && !respTokens.contains("close")
        val respHead = java.io.ByteArrayOutputStream(1024)
        resp.statusLine?.let { respHead.write("$it\r\n".toByteArray(Charsets.ISO_8859_1)) }
        writeHeaders(respHead, forwardHeaders(outRespHeaders, outBody, connectionClose = !keepClient))
        if (outBody != null && outBody.size <= 64 * 1024) respHead.write(outBody) // small bodies ride along in the same write
        clientOut.write(respHead.toByteArray())
        if (outBody != null && outBody.size > 64 * 1024) clientOut.write(outBody)
        clientOut.flush()
        stats.bytesDown.addAndGet(respHead.size().toLong() + (if (outBody != null && outBody.size > 64 * 1024) outBody.size else 0))
        stats.requests.incrementAndGet()

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
                responseStatus = resp.status,
                responseHeaders = resp.headers,
                responseBody = resp.body?.take(maxCapturedBodyBytes),
                durationMs = Instant.now().toEpochMilli() - start.toEpochMilli(),
            ),
            target.host,
        )
        return keepClient
    }

    /** Relays a `text/event-stream` answer chunk by chunk, recording each event as it passes. The client connection ends with the stream. */
    private fun streamEvents(
        clientSocket: Socket, clientOut: OutputStream, upstream: Upstream, upIn: InputStream, head: ResponseHead,
        txId: String, start: Instant, req: ReqEdit, scheme: String, target: Target,
    ) {
        runCatching { clientSocket.soTimeout = 0 }
        upstream.setTimeout(0)
        val inScope = !respectScope || scope.matches(target.host)
        val events = if (inScope) store.webSockets.open(txId, "sse") else null
        val sse = SseParser { text, truncated, length -> events?.add(false, 1, true, length, text.toByteArray(Charsets.UTF_8), truncated) }
        val chunked = headerValue(head.headers, "Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
        val outHeaders = head.headers.filterNot { it.first.equals("Connection", ignoreCase = true) } + ("Connection" to "close")
        val respHead = java.io.ByteArrayOutputStream(1024)
        respHead.write("${head.statusLine}\r\n".toByteArray(Charsets.ISO_8859_1))
        writeHeaders(respHead, outHeaders)
        try {
            clientOut.write(respHead.toByteArray()); clientOut.flush()
            recordIfInScope(
                HttpTransaction(
                    id = txId, source = source, timestamp = start, method = req.method, scheme = scheme,
                    host = target.host, port = target.port, path = req.target, requestHeaders = req.headers,
                    requestBody = req.body?.take(maxCapturedBodyBytes),
                    responseStatus = head.status, responseHeaders = head.headers,
                    durationMs = Instant.now().toEpochMilli() - start.toEpochMilli(),
                ),
                target.host,
            )
            stats.requests.incrementAndGet()
            val buf = ByteArray(8192)
            if (chunked) {
                // Forward each chunk as it is (size line, data, CRLF) and feed only the data to the event reader.
                while (true) {
                    val sizeLine = readLine(upIn) ?: break
                    clientOut.write((sizeLine + "\r\n").toByteArray(Charsets.ISO_8859_1)); clientOut.flush()
                    var remaining = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: break
                    if (remaining == 0L) {
                        // Trailers (usually none) up to the blank line, then the stream is over.
                        while (true) { val t = readLine(upIn) ?: break; clientOut.write((t + "\r\n").toByteArray(Charsets.ISO_8859_1)); if (t.isEmpty()) break }
                        clientOut.flush()
                        break
                    }
                    while (remaining > 0) {
                        val n = upIn.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) return
                        clientOut.write(buf, 0, n); clientOut.flush()
                        stats.bytesDown.addAndGet(n.toLong())
                        runCatching { sse.feed(buf, 0, n) }
                        remaining -= n
                    }
                    readLine(upIn)?.let { clientOut.write("\r\n".toByteArray()); clientOut.flush() } // the CRLF after the data
                }
            } else {
                while (true) {
                    val n = upIn.read(buf)
                    if (n < 0) break
                    clientOut.write(buf, 0, n); clientOut.flush()
                    stats.bytesDown.addAndGet(n.toLong())
                    runCatching { sse.feed(buf, 0, n) }
                }
            }
        } catch (_: IOException) {
            // Either side went away: the stream is over.
        } finally {
            upstream.close()
            runCatching { clientSocket.close() }
        }
    }

    private fun sendError(out: OutputStream, code: Int, reason: String, message: String) {
        runCatching {
            val body = message.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1),
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

    /**
     * Runs the request interceptor, bounded in time. Returns true when the interceptor
     * failed (threw, or did not answer within 30s) — the caller answers 502 and records
     * the drop. A hanging or crashing interceptor must never stall the proxy thread, or
     * silently forward traffic it was supposed to inspect. Cancellation still propagates.
     */
    private suspend fun applyRequestIntercept(id: String, scheme: String, host: String, port: Int, r: ReqEdit): Boolean {
        val icept = interceptor ?: return false
        val a = try {
            withTimeoutOrNull(INTERCEPT_TIMEOUT_MS) {
                icept.onRequest(InterceptedRequest(id, source, r.method, scheme, host, port, r.target, r.headers, r.body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return true
        when (a) {
            RequestAction.Forward -> {}
            RequestAction.Drop -> r.drop = true
            is RequestAction.Replace -> {
                a.method?.let { r.method = it }
                a.target?.let { r.target = it }
                a.headers?.let { r.headers = it }
                if (a.body != null) {
                    r.body = a.body
                    // The replacement body is plain bytes: a stale Content-Encoding (gzip, …)
                    // would make the peer decode garbage, so strip it with the re-framing.
                    r.headers = stripContentEncoding(withContentLength(r.headers, a.body.size))
                }
            }
        }
        return false
    }

    /** Same contract as [applyRequestIntercept], for responses. */
    private suspend fun applyResponseIntercept(id: String, host: String, port: Int, r: RespEdit): Boolean {
        val icept = interceptor ?: return false
        val a = try {
            withTimeoutOrNull(INTERCEPT_TIMEOUT_MS) {
                icept.onResponse(InterceptedResponse(id, source, host, port, r.status, r.headers, r.body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return true
        when (a) {
            ResponseAction.Forward -> {}
            ResponseAction.Drop -> r.drop = true
            is ResponseAction.Replace -> {
                a.status?.let { r.status = it; r.statusLine = replaceStatusCode(r.statusLine, it) }
                a.headers?.let { r.headers = it }
                if (a.body != null) {
                    r.body = a.body
                    r.headers = stripContentEncoding(withContentLength(r.headers, a.body.size))
                }
            }
        }
        return false
    }

    private fun stripContentEncoding(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.filterNot { it.first.equals("Content-Encoding", ignoreCase = true) }

    /** GET/HEAD/OPTIONS/TRACE: replaying one is safe even if the first attempt reached the server. */
    private fun isIdempotent(method: String): Boolean =
        method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true) ||
            method.equals("OPTIONS", ignoreCase = true) || method.equals("TRACE", ignoreCase = true)

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
     * Headers to forward: strip hop-by-hop headers, and — because we always buffer and may
     * de-chunk or truncate the body — re-frame Content-Length to the bytes we will actually write and drop any
     * stale Transfer-Encoding (so the peer never waits on chunks that won't come, and never on a length that
     * doesn't match). A bodyless message keeps whatever framing it had.
     *
     * Hop-by-hop (RFC 9110 §7.6.1) headers are for this connection only and must not be relayed
     * to the next hop: Keep-Alive, TE, Trailer, Upgrade (except on a genuine upgrade handshake,
     * where it *is* the request), Proxy-Authenticate, Proxy-Authorization, Proxy-Connection,
     * and every header named by the Connection header itself. Connection itself is rewritten
     * only when we close the connection ourselves; an Upgrade handshake keeps its own.
     */
    private fun forwardHeaders(headers: List<Pair<String, String>>, body: ByteArray?, connectionClose: Boolean): List<Pair<String, String>> {
        val connTokens = headerValue(headers, "Connection")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
        // A genuine upgrade handshake (Connection: Upgrade + Upgrade: websocket) keeps both
        // headers — stripping them would break the handshake this function is forwarding.
        val isUpgradeHandshake = "upgrade" in connTokens
        val hopByHop = setOf("proxy-connection", "keep-alive", "te", "trailer", "proxy-authenticate", "proxy-authorization") +
            connTokens.filterNot { it == "close" || it == "keep-alive" || (it == "upgrade" && isUpgradeHandshake) } +
            if (isUpgradeHandshake) emptySet() else setOf("upgrade")
        var h = headers.filterNot { header ->
            val name = header.first.lowercase()
            name in hopByHop || (connectionClose && name == "connection")
        }
        if (body != null) h = withContentLength(h, body.size) // also drops Transfer-Encoding: re-framed, not chunked
        return if (connectionClose) h + ("Connection" to "close") else h
    }

    private suspend fun pump(
        from: InputStream, to: OutputStream, counter: java.util.concurrent.atomic.AtomicLong, tap: ((ByteArray, Int, Int) -> Unit)? = null,
    ) = withContext(connDispatcher) {
        val buffer = ByteArray(8192)
        try {
            while (isActive) {
                val n = from.read(buffer)
                if (n == -1) break
                to.write(buffer, 0, n)
                to.flush()
                counter.addAndGet(n.toLong())
                // Looking at the bytes must never be able to break the relay.
                if (tap != null) runCatching { tap(buffer, 0, n) }
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
        /** How long an interceptor (breakpoint) may hold one message before the proxy answers 502. */
        const val INTERCEPT_TIMEOUT_MS = 30_000L
        /** A peer that goes silent this long mid-message is dropped instead of pinning a thread and socket forever. */
        const val IDLE_TIMEOUT_MS = 60_000
        /** How long an idle keep-alive tunnel is held (it pins a thread while it waits for the next request). */
        const val KEEPALIVE_IDLE_MS = 20_000
        const val MAX_REQUESTS_PER_TUNNEL = 200

        /**
         * Connection handlers block on socket reads, so they get their own cached (unbounded, daemon) pool rather than
         * Dispatchers.IO's 64 threads: a browser holding many idle keep-alive tunnels must never starve new requests.
         * The connection cap in [MAX_CONNECTIONS] bounds the real thread count.
         */
        val connDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "alpproxy-conn").apply { isDaemon = true } }
                .asCoroutineDispatcher()
    }
}
