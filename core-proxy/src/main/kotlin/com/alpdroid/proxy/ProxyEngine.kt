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
 * Chunked transfer-encoding is not yet parsed — a chunked response is
 * relayed to the client correctly (raw bytes are forwarded either way) but
 * is not logged into History. This is a known v0 gap, not a silent bug.
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
                launch(Dispatchers.IO) {
                    try {
                        handleClient(client)
                    } catch (e: IOException) {
                        // Connection dropped mid-flight; nothing to recover.
                    } finally {
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
            val intercepted = runCatching { handleMitmTunnel(client, target, mitm) }.isSuccess
            if (intercepted) return
            // else: handshake (or worse) failed mid-flight on this socket — it can't be
            // reused for an opaque tunnel, so close it rather than log a misleading entry.
            return
        }

        val upstream = try {
            Socket().apply { connect(InetSocketAddress(target.host, target.port), 10_000) }
        } catch (e: IOException) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }
        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())

        upstream.use {
            withContext(Dispatchers.IO) {
                val toUpstream = launch { pump(client.getInputStream(), upstream.getOutputStream()) }
                val toClient = launch { pump(upstream.getInputStream(), client.getOutputStream()) }
                toUpstream.join()
                toClient.join()
            }
        }
    }

    /**
     * Terminates the client's TLS with our leaf certificate, opens a real TLS session
     * upstream, and relays the decrypted HTTP with full capture. After CONNECT the client
     * speaks origin-form (`GET /path`), not proxy absolute-form — the target comes from
     * the CONNECT itself, not the request line.
     */
    private suspend fun handleMitmTunnel(client: Socket, target: Target, mitm: TlsMitm) {
        withContext(Dispatchers.IO) {
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
        }
        val tlsClient = withContext(Dispatchers.IO) {
            mitm.wrapClient(client, target.host).also { it.startHandshake() }
        }
        val upstream = withContext(Dispatchers.IO) {
            mitm.upstreamSocket(target.host, target.port).also { it.startHandshake() }
        }
        upstream.use {
            tlsClient.use {
                relayDecrypted(tlsClient, upstream, target)
            }
        }
    }

    private suspend fun relayDecrypted(clientTls: SSLSocket, upstreamTls: SSLSocket, target: Target) {
        val start = Instant.now()
        val clientInput = BufferedInputStream(clientTls.inputStream)
        val requestLine = readLine(clientInput)?.let(::parseRequestLine) ?: return
        val requestHeaders = readHeaders(clientInput)
        val requestBody = readBodyIfPresent(clientInput, requestHeaders)

        val txId = UUID.randomUUID().toString()
        val req = ReqEdit(requestLine.method, requestLine.target.ifEmpty { "/" }, requestHeaders, requestBody)
        applyRequestIntercept(txId, "https", target.host, target.port, req)
        if (req.drop) {
            recordDropped(txId, start, req.method, "https", target.host, target.port, req.target, req.headers, req.body)
            return
        }

        val upstreamOut = upstreamTls.outputStream
        writeRequestLine(upstreamOut, req.method, req.target, requestLine.version)
        val forwardedHeaders = req.headers
            .filterNot { it.first.equals("Proxy-Connection", ignoreCase = true) }
            .filterNot { it.first.equals("Connection", ignoreCase = true) } + ("Connection" to "close")
        writeHeaders(upstreamOut, forwardedHeaders)
        req.body?.let { upstreamOut.write(it) }
        upstreamOut.flush()

        val upstreamInput = BufferedInputStream(upstreamTls.inputStream)
        val statusLine = readLine(upstreamInput)
        val statusCode = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
        val responseHeaders = readHeaders(upstreamInput)
        val responseBody = readBodyIfPresent(upstreamInput, responseHeaders)

        val resp = RespEdit(statusLine, statusCode, responseHeaders, responseBody)
        applyResponseIntercept(txId, target.host, target.port, resp)
        if (!resp.drop) {
            val clientOut = clientTls.outputStream
            resp.statusLine?.let { clientOut.write("$it\r\n".toByteArray(Charsets.US_ASCII)) }
            writeHeaders(clientOut, resp.headers)
            resp.body?.let { clientOut.write(it) }
            clientOut.flush()
        }

        recordIfInScope(
            HttpTransaction(
                id = txId,
                source = source,
                timestamp = start,
                method = req.method,
                scheme = "https",
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

    private suspend fun handleHttp(
        client: Socket,
        clientInput: InputStream,
        requestLine: RequestLine,
        requestHeaders: List<Pair<String, String>>,
        target: Target,
    ) {
        val start = Instant.now()
        val requestBody = readBodyIfPresent(clientInput, requestHeaders)

        val upstream = try {
            Socket().apply { connect(InetSocketAddress(target.host, target.port), 10_000) }
        } catch (e: IOException) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }

        val txId = UUID.randomUUID().toString()
        val req = ReqEdit(requestLine.method, target.path.ifEmpty { "/" }, requestHeaders, requestBody)
        applyRequestIntercept(txId, target.scheme, target.host, target.port, req)
        if (req.drop) {
            upstream.close()
            recordDropped(txId, start, req.method, target.scheme, target.host, target.port, req.target, req.headers, req.body)
            return
        }

        upstream.use {
            val upstreamOut = upstream.getOutputStream()
            writeRequestLine(upstreamOut, req.method, req.target, requestLine.version)
            val forwardedHeaders = req.headers
                .filterNot { it.first.equals("Proxy-Connection", ignoreCase = true) }
                .filterNot { it.first.equals("Connection", ignoreCase = true) } + ("Connection" to "close")
            writeHeaders(upstreamOut, forwardedHeaders)
            req.body?.let { upstreamOut.write(it) }
            upstreamOut.flush()

            val upstreamInput = BufferedInputStream(upstream.getInputStream())
            val statusLine = readLine(upstreamInput)
            val statusCode = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()
            val responseHeaders = readHeaders(upstreamInput)
            val responseBody = readBodyIfPresent(upstreamInput, responseHeaders)

            val resp = RespEdit(statusLine, statusCode, responseHeaders, responseBody)
            applyResponseIntercept(txId, target.host, target.port, resp)
            if (!resp.drop) {
                val clientOut = client.getOutputStream()
                resp.statusLine?.let { clientOut.write("$it\r\n".toByteArray(Charsets.US_ASCII)) }
                writeHeaders(clientOut, resp.headers)
                resp.body?.let { clientOut.write(it) }
                clientOut.flush()
            }

            recordIfInScope(
                HttpTransaction(
                    id = txId,
                    source = source,
                    timestamp = start,
                    method = req.method,
                    scheme = target.scheme,
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

    private fun readBodyIfPresent(input: InputStream, headers: List<Pair<String, String>>): ByteArray? {
        val length = headerValue(headers, "Content-Length")?.toIntOrNull() ?: return null
        if (length <= 0) return null
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n == -1) break
            read += n
        }
        return if (read == length) buffer else buffer.copyOf(read)
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
}
