package com.alpdroid.proxy

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TlsMitmTest {

    @Test
    fun `server contexts are cached per host`() {
        val mitm = freshTestMitm()
        assertSame(mitm.serverContext("a.test"), mitm.serverContext("a.test"))
    }

    /**
     * Full TLS handshake over loopback: the server side presents our leaf for the requested
     * host, the client side trusts only our root CA. If the keystore/KeyManager wiring or
     * the leaf chain is wrong in any way, the handshake throws instead of completing —
     * this is the riskiest blind-written part of the MITM path, so it gets a real test.
     */
    @Test
    fun `handshake completes for a client trusting only our CA`() {
        val mitm = freshTestMitm()
        val listener = ServerSocket(0)
        try {
            thread(isDaemon = true) {
                val raw = listener.accept()
                val tls = mitm.wrapClient(raw, "example.test")
                tls.startHandshake()
                val line = readLine(BufferedInputStream(tls.inputStream))
                tls.outputStream.write("got:$line\r\n".toByteArray(Charsets.US_ASCII))
                tls.outputStream.flush()
                tls.close()
            }

            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ronin-test-ca", mitm.ca.certificate)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trustStore)
            }
            val clientCtx = SSLContext.getInstance("TLS").apply {
                init(null, tmf.trustManagers, null)
            }
            val socket = clientCtx.socketFactory
                .createSocket("127.0.0.1", listener.localPort) as SSLSocket
            socket.use {
                it.startHandshake()
                it.outputStream.write("hello\r\n".toByteArray(Charsets.US_ASCII))
                it.outputStream.flush()
                val reply = readLine(BufferedInputStream(it.inputStream))
                assertTrue(reply == "got:hello", "unexpected reply: $reply")
            }
        } finally {
            listener.close()
        }
    }
}
