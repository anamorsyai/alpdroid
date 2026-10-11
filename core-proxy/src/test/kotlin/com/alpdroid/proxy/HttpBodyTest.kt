package com.alpdroid.proxy

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HttpBodyTest {
    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1))

    @Test fun contentLengthBodyReadExactly() {
        val r = readMessageBody(stream("hello world!!"), listOf("Content-Length" to "5"), cap = 1024, allowEofBody = true)
        assertEquals("hello", String(r.bytes!!, Charsets.ISO_8859_1))
        assertFalse(r.truncated)
    }

    @Test fun contentLengthZeroIsNoBody() {
        val r = readMessageBody(stream(""), listOf("Content-Length" to "0"), cap = 1024, allowEofBody = true)
        assertNull(r.bytes)
    }

    @Test fun contentLengthOverCapTruncates() {
        val r = readMessageBody(stream("abcdefghij"), listOf("Content-Length" to "10"), cap = 4, allowEofBody = true)
        assertEquals("abcd", String(r.bytes!!, Charsets.ISO_8859_1))
        assertTrue(r.truncated)
    }

    @Test fun chunkedBodyIsDechunked() {
        // "Wikipedia" in two chunks, per RFC 7230 example shape.
        val body = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val r = readMessageBody(stream(body), listOf("Transfer-Encoding" to "chunked"), cap = 1024, allowEofBody = true)
        assertEquals("Wikipedia", String(r.bytes!!, Charsets.ISO_8859_1))
        assertFalse(r.truncated)
    }

    @Test fun chunkedWithExtensionSizeToken() {
        val body = "3;ext=1\r\nabc\r\n0\r\n\r\n"
        val r = readMessageBody(stream(body), listOf("transfer-encoding" to "chunked"), cap = 1024, allowEofBody = true)
        assertEquals("abc", String(r.bytes!!, Charsets.ISO_8859_1))
    }

    @Test fun eofFramedResponseReadsToClose() {
        val r = readMessageBody(stream("no length here"), emptyList(), cap = 1024, allowEofBody = true)
        assertEquals("no length here", String(r.bytes!!, Charsets.ISO_8859_1))
    }

    @Test fun requestWithoutFramingHasNoBody() {
        // allowEofBody=false (a request): no Content-Length and no chunked means no body, even with bytes present.
        val r = readMessageBody(stream("stray bytes"), emptyList(), cap = 1024, allowEofBody = false)
        assertNull(r.bytes)
    }
}
