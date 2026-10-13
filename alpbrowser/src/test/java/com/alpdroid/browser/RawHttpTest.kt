package com.alpdroid.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHttpTest {
    @Test fun aLongTextBodyIsShownEvenWithAStrayControlByteOrAnOtherCharset() {
        val ct = listOf("Content-Type" to "text/html; charset=windows-1252")
        // one invalid-UTF-8 byte (é in Latin-1) deep in a long body used to turn the whole body into "[binary body]"
        val latin = ("a".repeat(5000) + "caf").toByteArray() + byteArrayOf(0xE9.toByte()) + ("b".repeat(5000)).toByteArray()
        assertTrue(RawHttp.displayText(ct, latin)!!.contains("café"))
        // a stray ESC / NUL deep in the body is shown as a dot, not as "binary"
        val stray = ("{\"k\":\"" + "x".repeat(20000)).toByteArray() + byteArrayOf(0, 27) + "\"}".toByteArray()
        val shown = RawHttp.displayText(listOf("Content-Type" to "application/json"), stray)!!
        assertTrue(shown.startsWith("{\"k\"") && shown.endsWith("\"}") && shown.contains("·"))
        // real binary stays binary
        assertNull(RawHttp.displayText(listOf("Content-Type" to "image/png"), byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0)))
        assertNull(RawHttp.displayText(emptyList(), ByteArray(4000) { (it % 7).toByte() }))
        assertEquals("", RawHttp.displayText(emptyList(), null))
    }

    @Test fun `headers round trip and keep colons in values`() {
        val h = listOf("Host" to "a.test", "Referer" to "https://a.test:8443/x")
        assertEquals(h, RawHttp.parseHeaders(RawHttp.headersToText(h)))
    }

    @Test fun `junk header lines are ignored`() {
        assertEquals(listOf("A" to "1"), RawHttp.parseHeaders("A: 1\nno colon here\n: nameless\nbad name: x\n"))
    }

    @Test fun `binary bodies are not editable as text`() {
        assertNull(RawHttp.bodyToText(byteArrayOf(0, 1, 2, 3)))
        assertNull(RawHttp.bodyToText(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertEquals("héllo\n", RawHttp.bodyToText("héllo\n".toByteArray()))
        assertEquals("", RawHttp.bodyToText(null))
    }

    @Test fun `default ports are dropped from display urls`() {
        assertEquals("https://a.test/p?q=1", RawHttp.displayUrl("https", "a.test", 443, "/p?q=1"))
        assertEquals("http://a.test:8080/", RawHttp.displayUrl("http", "a.test", 8080, ""))
    }

    @Test fun `splitUrl accepts only http and https`() {
        assertEquals(Triple("https", "a.test:8443", "/x?y=1"), RawHttp.splitUrl("https://a.test:8443/x?y=1"))
        assertEquals(Triple("http", "a.test", "/"), RawHttp.splitUrl("http://a.test"))
        assertNull(RawHttp.splitUrl("file:///etc/passwd"))
        assertNull(RawHttp.splitUrl("a.test/x"))
    }
}
