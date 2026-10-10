package com.alpdroid.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpMessageTest {

    @Test
    fun `parses absolute-URI GET target with explicit port`() {
        val target = parseTarget("GET", "http://example.com:8080/foo/bar?x=1")
        assertEquals(Target("http", "example.com", 8080, "/foo/bar?x=1"), target)
    }

    @Test
    fun `defaults to port 80 for http and 443 for https when omitted`() {
        assertEquals(80, parseTarget("GET", "http://example.com/")?.port)
        assertEquals(443, parseTarget("GET", "https://example.com/")?.port)
    }

    @Test
    fun `parses CONNECT target as host colon port`() {
        val target = parseTarget("CONNECT", "example.com:443")
        assertEquals(Target("https", "example.com", 443, ""), target)
    }

    @Test
    fun `rejects a CONNECT target with no port`() {
        assertNull(parseTarget("CONNECT", "example.com"))
    }

    @Test
    fun `parses a well formed request line`() {
        val line = parseRequestLine("GET http://example.com/ HTTP/1.1")
        assertEquals(RequestLine("GET", "http://example.com/", "HTTP/1.1"), line)
    }

    @Test
    fun `rejects a malformed request line`() {
        assertNull(parseRequestLine("not a request line"))
    }

    @Test
    fun `reads CRLF-terminated lines and headers`() {
        val raw = "GET / HTTP/1.1\r\nHost: example.com\r\nX-Test: value\r\n\r\nbody".byteInputStream()
        assertEquals("GET / HTTP/1.1", readLine(raw))
        val headers = readHeaders(raw)
        assertEquals(listOf("Host" to "example.com", "X-Test" to "value"), headers)
    }
}
