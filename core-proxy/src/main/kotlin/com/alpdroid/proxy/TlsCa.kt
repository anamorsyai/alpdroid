package com.alpdroid.proxy

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * The certificate authority behind TLS interception (Phase 2).
 *
 * One self-signed root CA is generated on first app launch and persisted in app-private
 * storage ([CaMaterial]); every HTTPS host the proxy intercepts gets its own short-lived
 * leaf certificate signed by it. Clients trust the interception only because they trust
 * this exact CA — the WebView via its network security config and Alpine tools via the
 * CA appended to the rootfs bundle (see the app module's trust wiring, not here).
 *
 * Pure JVM (BouncyCastle) with zero Android dependency, so it builds and unit-tests
 * anywhere core-proxy does.
 */
data class CaMaterial(val keyPair: KeyPair, val certificate: X509Certificate)

data class LeafMaterial(val keyPair: KeyPair, val certificate: X509Certificate)

object TlsCa {
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val CA_VALIDITY_DAYS = 3650L
    private const val LEAF_VALIDITY_DAYS = 825L

    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        return gen.generateKeyPair()
    }

    /** A self-signed root CA: CA:true, cert-signing key usage, 10-year validity. */
    fun selfSignedCa(commonName: String = "Ronin Proxy CA"): CaMaterial {
        val keyPair = generateKeyPair()
        val subject = X500Name("CN=$commonName")
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            freshSerial(),
            Date(now.time - 24L * 3600_000),
            Date(now.time + CA_VALIDITY_DAYS * 24L * 3600_000),
            subject,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        val holder = builder.build(JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private))
        return CaMaterial(keyPair, JcaX509CertificateConverter().getCertificate(holder))
    }

    /**
     * A leaf certificate for [host], signed by [ca]. Carries a SAN (DNS or IP as
     * appropriate) because modern TLS stacks ignore CN — a CN-only leaf would fail
     * hostname verification everywhere even with the CA trusted.
     */
    fun issueLeaf(ca: CaMaterial, host: String): LeafMaterial {
        val keyPair = generateKeyPair()
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(ca.certificate.subjectX500Principal.name),
            freshSerial(),
            Date(now.time - 24L * 3600_000),
            Date(now.time + LEAF_VALIDITY_DAYS * 24L * 3600_000),
            X500Name("CN=$host"),
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        val san = if (isIpLiteral(host)) {
            GeneralNames(GeneralName(GeneralName.iPAddress, host))
        } else {
            GeneralNames(GeneralName(GeneralName.dNSName, host))
        }
        builder.addExtension(Extension.subjectAlternativeName, false, san)
        val holder = builder.build(JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(ca.keyPair.private))
        return LeafMaterial(keyPair, JcaX509CertificateConverter().getCertificate(holder))
    }

    fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    fun encodeCertificate(cert: X509Certificate): String = pemEncode("CERTIFICATE", cert.encoded)

    fun decodeCertificate(pem: String): X509Certificate {
        val bytes = pemDecode(pem, "CERTIFICATE")
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    fun encodePrivateKey(key: PrivateKey): String = pemEncode("PRIVATE KEY", key.encoded)

    fun decodePrivateKey(pem: String): PrivateKey {
        val bytes = pemDecode(pem, "PRIVATE KEY")
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun freshSerial(): BigInteger {
        var serial = BigInteger(64, SecureRandom())
        if (serial.signum() <= 0) serial = serial.negate().add(BigInteger.ONE)
        return serial
    }

    private fun pemEncode(type: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
    }

    private fun pemDecode(pem: String, type: String): ByteArray {
        val stripped = pem
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
            .filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(stripped)
    }
}
