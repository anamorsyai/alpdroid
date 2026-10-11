package com.alpdroid.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAccessTest {
    @Test fun `plain loopback clients are allowed`() {
        assertTrue(LocalAccess.allowed(mapOf("host" to "127.0.0.1:8899")))
        assertTrue(LocalAccess.allowed(mapOf("host" to "localhost")))
        assertTrue(LocalAccess.allowed(mapOf("host" to "[::1]:9222")))
    }

    @Test fun `page-made requests and rebound names are refused`() {
        assertFalse(LocalAccess.allowed(mapOf("host" to "127.0.0.1:8899", "origin" to "https://x.test")))
        assertFalse(LocalAccess.allowed(mapOf("host" to "127.0.0.1:8899", "sec-fetch-site" to "none")))
        assertFalse(LocalAccess.allowed(mapOf("host" to "evil.test:8899")))
        assertFalse(LocalAccess.allowed(mapOf("host" to "127.0.0.1.evil.test")))
        assertFalse(LocalAccess.allowed(emptyMap()))
    }
}
