package com.alpdroid.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeFilterTest {

    @Test
    fun `empty pattern list matches everything`() {
        val filter = ScopeFilter()
        assertTrue(filter.matches("anything.example.com"))
    }

    @Test
    fun `wildcard pattern matches subdomains but not the bare domain`() {
        val filter = ScopeFilter()
        filter.setPatterns(listOf("*.target.com"))
        assertTrue(filter.matches("api.target.com"))
        assertFalse(filter.matches("target.com"))
        assertFalse(filter.matches("evil.com"))
    }

    @Test
    fun `exact host pattern matches case-insensitively`() {
        val filter = ScopeFilter()
        filter.setPatterns(listOf("Target.com"))
        assertTrue(filter.matches("target.com"))
        assertFalse(filter.matches("sub.target.com"))
    }
}
