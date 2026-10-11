package com.alpdroid.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieHeaderTest {
    private fun ok(c: CookieSpec) = CookieHeader.build(c).getOrThrow()
    private fun bad(c: CookieSpec) = CookieHeader.build(c).exceptionOrNull()!!.message!!

    @Test fun `a plain cookie keeps the old shape`() {
        assertEquals("sid=1; Path=/", ok(CookieSpec("sid", "1")))
        assertEquals("sid=1; Path=/x; Domain=a.test", ok(CookieSpec("sid", "1", domain = "a.test", path = "/x")))
    }

    @Test fun `every attribute is written`() {
        val s = ok(CookieSpec("t", "v", "a.test", "/", secure = true, httpOnly = true, sameSite = "lax", maxAge = 60, expires = 0))
        assertEquals("t=v; Path=/; Domain=a.test; Secure; HttpOnly; SameSite=Lax; Max-Age=60; Expires=Thu, 01 Jan 1970 00:00:00 GMT", s)
    }

    @Test fun `values that could smuggle attributes are refused`() {
        assertTrue(bad(CookieSpec("a", "x; Domain=evil.test")).contains("';'"))
        assertTrue(bad(CookieSpec("a", "x\r\nSet-Cookie: b=c")).contains("line breaks"))
        assertTrue(bad(CookieSpec("a b", "x")).contains("name"))
        assertTrue(bad(CookieSpec("a", "x", domain = "a.test; Secure")).contains("domain"))
        assertTrue(bad(CookieSpec("a", "x", path = "x")).contains("path"))
        assertTrue(bad(CookieSpec("a", "x", sameSite = "weird")).contains("samesite"))
        assertTrue(bad(CookieSpec("a", "x", sameSite = "None")).contains("--secure"))
        assertEquals("a=x; Path=/; Secure; SameSite=None", ok(CookieSpec("a", "x", secure = true, sameSite = "None")))
    }
}
