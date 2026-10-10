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
    fun serverContext(host: String): SSLContext =
        serverContexts.getOrPut(host) {
            val leaf = TlsCa.issueLeaf(ca, host)
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

    /** Client-mode TLS socket to the real upstream, trusting the platform's system CAs. */
    fun upstreamSocket(host: String, port: Int): SSLSocket =
        SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket

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
}

/** Convenience for tests: an in-memory CA, no persistence involved. */
fun freshTestMitm(): TlsMitm = TlsMitm(TlsCa.selfSignedCa("Ronin Test CA"))

/** Opens a plain TCP socket with a connect timeout — shared by the engine's upstream paths. */
internal fun openUpstream(host: String, port: Int, timeoutMs: Int = 10_000): java.net.Socket =
    java.net.Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
