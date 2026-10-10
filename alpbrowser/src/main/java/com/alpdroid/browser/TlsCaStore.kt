package com.alpdroid.browser

import android.content.Context
import android.util.Log
import com.alpdroid.proxy.CaMaterial
import com.alpdroid.proxy.TlsCa
import java.io.File
import java.security.KeyPair

/**
 * The proxy's root CA, generated once and persisted in app-private storage. Every HTTPS host
 * the proxy intercepts gets a leaf signed by this exact CA (see core-proxy's [TlsCa]), so
 * clients only trust the interception when they trust this certificate:
 * - the WebView accepts it via [com.alpdroid.browser.ui.browser.BrowserScreen]'s ssl-error handling
 *   (proceed only on UNTRUSTED — every byte of browser traffic routes through our proxy, so
 *   the only untrusted certs it can ever see are our own leaves);
 * - Alpine tools (curl, python, ...) validate against the system bundle, so the CA is
 *   appended to `<rootfs>/etc/ssl/certs/ca-certificates.crt` on every session start
 *   ([ensureCaInBundle] — re-appending is what survives a rootfs re-extract wiping it).
 */
class TlsCaStore(private val appContext: Context) {
    private val certFile = File(appContext.filesDir, "ronin-ca-cert.pem")
    private val keyFile = File(appContext.filesDir, "ronin-ca-key.pem")

    /** Generates on first launch (~RSA-2048 keygen, well under a second), loads after that. */
    fun loadOrGenerate(): CaMaterial {
        if (certFile.isFile && keyFile.isFile) {
            val loaded = runCatching {
                val cert = TlsCa.decodeCertificate(certFile.readText())
                CaMaterial(KeyPair(cert.publicKey, TlsCa.decodePrivateKey(keyFile.readText())), cert)
            }.getOrNull()
            if (loaded != null) return loaded
            Log.w(TAG, "Stored CA unreadable, regenerating")
        }
        val fresh = TlsCa.selfSignedCa()
        certFile.writeText(TlsCa.encodeCertificate(fresh.certificate))
        keyFile.writeText(TlsCa.encodePrivateKey(fresh.keyPair.private))
        // NOTE: no setReadable/setWritable tightening here — filesDir is already app-private
        // (MODE_PRIVATE), and setReadable(false, false) revokes OWNER read too, which made
        // every launch fail to read the stored CA back ("Stored CA unreadable, regenerating"
        // on every start, plus a stale CA copy accumulating in the Alpine bundle).
        return fresh
    }

    fun ensureCaInBundle(rootfs: File, caPem: String) {
        runCatching {
            val bundle = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
            if (!bundle.isFile) {
                Log.w(TAG, "No CA bundle in rootfs at ${bundle.absolutePath}, skipping append")
                return
            }
            val current = bundle.readText()
            if (MARKER in current) return
            bundle.appendText("\n$MARKER\n$caPem")
        }.onFailure { Log.w(TAG, "Could not append CA to rootfs bundle", it) }
    }

    companion object {
        private const val TAG = "Ronin/TlsCa"
        private const val MARKER = "# ronin-proxy-ca (appended by Ronin, re-added if missing)"
    }
}
