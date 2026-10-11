package com.alpdroid.proxy

import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Server side of TLS interception: per-host [SSLContext]s built from leaf certificates
 * issued by our root [ca] (see [TlsCa]). Leaf issuance involves an RSA key generation, so
 * contexts are cached per host — without this, every single CONNECT would pay ~100ms+
 * of keygen before the handshake even starts.
 *
 * Pure JVM (javax.net.ssl), no Android dependency — the engine's unit-test story stays intact.
 */
class TlsMitm(val ca: CaMaterial) {
    private val serverContexts = ConcurrentHashMap<String, SSLContext>()

    /** Server-mode context presenting a leaf certificate for [host]. */
    fun serverContext(host: String): SSLContext {
        serverContexts[host]?.let { return it }
        // Bound the cache: a very long session touching thousands of distinct hosts would otherwise
        // keep one leaf cert + context per host forever. Past the cap, drop them all and rebuild lazily.
        if (serverContexts.size > MAX_CONTEXTS) serverContexts.clear()
        // computeIfAbsent (not getOrPut): atomic per key, so a burst of parallel first-hits for one new
        // host runs the ~100ms RSA leaf keygen exactly once instead of once per connection.
        return serverContexts.computeIfAbsent(host) { h ->
            val leaf = TlsCa.issueLeaf(ca, h)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("leaf", leaf.keyPair.private, CharArray(0), arrayOf(leaf.certificate, ca.certificate))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(ks, CharArray(0))
            }
            SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, SecureRandom())
            }
        }
    }

    /**
     * Client-mode TLS socket to the real upstream. Validates the real server's certificate against the
     * platform's system CAs AND verifies its hostname: a raw SSLSocket does NO hostname check by default
     * (unlike HttpsURLConnection), so without `endpointIdentificationAlgorithm = "HTTPS"` a network
     * attacker presenting any system-CA-valid cert for a different host would be silently accepted. The
     * plain socket is connected with a timeout first so a black-holed host cannot pin a thread forever.
     */
    fun upstreamSocket(host: String, port: Int): SSLSocket {
        val plain = java.net.Socket()
        plain.connect(InetSocketAddress(host, port), UPSTREAM_CONNECT_TIMEOUT_MS)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        // Pass host/port so SNI and hostname verification use the real name, not the resolved IP.
        val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        return ssl
    }

    /**
     * Wraps an already-connected, already-CONNECT-answered client socket in a server-mode
     * TLS session. Must be called after the `200 Connection Established` line was written —
     * the client starts its handshake the moment it reads that line.
     */
    fun wrapClient(socket: java.net.Socket, host: String): SSLSocket {
        val factory = serverContext(host).socketFactory as SSLSocketFactory
        val tls = factory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
        tls.useClientMode = false
        return tls
    }

    private companion object {
        const val MAX_CONTEXTS = 512
        const val UPSTREAM_CONNECT_TIMEOUT_MS = 10_000
    }
}

/** Convenience for tests: an in-memory CA, no persistence involved. */
fun freshTestMitm(): TlsMitm = TlsMitm(TlsCa.selfSignedCa("Ronin Test CA"))

/** Opens a plain TCP socket with a connect timeout — shared by the engine's upstream paths. */
internal fun openUpstream(host: String, port: Int, timeoutMs: Int = 10_000): java.net.Socket =
    java.net.Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
