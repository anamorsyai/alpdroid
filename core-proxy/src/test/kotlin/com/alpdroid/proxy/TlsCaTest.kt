package com.alpdroid.proxy

import java.security.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TlsCaTest {

    @Test
    fun `ca is self-signed with CA basic constraints`() {
        val ca = TlsCa.selfSignedCa()
        assertEquals(ca.certificate.subjectX500Principal, ca.certificate.issuerX500Principal)
        ca.certificate.verify(ca.keyPair.public) // throws if the self-signature is bad
        assertTrue(ca.certificate.basicConstraints != -1, "CA must carry basicConstraints CA:true")
    }

    @Test
    fun `leaf verifies against the CA and carries the host SAN`() {
        val ca = TlsCa.selfSignedCa()
        val leaf = TlsCa.issueLeaf(ca, "api.target.dev")
        leaf.certificate.verify(ca.keyPair.public) // throws if not really signed by this CA
        assertEquals(-1, leaf.certificate.basicConstraints, "leaf must not be a CA")
        val sans = leaf.certificate.subjectAlternativeNames
            .mapNotNull { it.getOrNull(1) as? String }
        assertTrue("api.target.dev" in sans, "leaf SANs $sans must include the host")
    }

    @Test
    fun `ip literal gets an IP SAN instead of DNS`() {
        val ca = TlsCa.selfSignedCa()
        val leaf = TlsCa.issueLeaf(ca, "127.0.0.1")
        leaf.certificate.verify(ca.keyPair.public)
        val sans = leaf.certificate.subjectAlternativeNames
            .mapNotNull { it.getOrNull(1) as? String }
        assertTrue("127.0.0.1" in sans, "leaf SANs $sans must include the IP")
    }

    @Test
    fun `certificate PEM round-trips`() {
        val ca = TlsCa.selfSignedCa()
        val restored = TlsCa.decodeCertificate(TlsCa.encodeCertificate(ca.certificate))
        assertEquals(ca.certificate, restored)
    }

    @Test
    fun `private key PEM round-trips and still signs`() {
        val ca = TlsCa.selfSignedCa()
        val restored = TlsCa.decodePrivateKey(TlsCa.encodePrivateKey(ca.keyPair.private))
        assertEquals(ca.keyPair.private, restored)
        // The restored key must still issue leaves the CA verifies.
        val recut = CaMaterial(KeyPair(ca.keyPair.public, restored), ca.certificate)
        val leaf = TlsCa.issueLeaf(recut, "x.test")
        leaf.certificate.verify(ca.keyPair.public)
    }

    @Test
    fun `two leaves for the same host differ`() {
        val ca = TlsCa.selfSignedCa()
        val a = TlsCa.issueLeaf(ca, "api.target.dev")
        val b = TlsCa.issueLeaf(ca, "api.target.dev")
        assertNotEquals(a.certificate.serialNumber, b.certificate.serialNumber)
    }
}
