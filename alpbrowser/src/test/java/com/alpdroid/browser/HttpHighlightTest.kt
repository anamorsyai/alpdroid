package com.alpdroid.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.alpdroid.browser.ui.proxy.HttpHighlight
import com.alpdroid.browser.ui.proxy.chunkHighlighted
import com.alpdroid.browser.ui.theme.Accent
import com.alpdroid.browser.ui.theme.Method
import com.alpdroid.browser.ui.theme.StatusError
import com.alpdroid.browser.ui.theme.StatusOk
import com.alpdroid.browser.ui.theme.StatusRedirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHighlightTest {
    /** The colour a given word is painted with (the first span that covers its first character). */
    private fun colorOf(a: AnnotatedString, word: String, occurrence: Int = 0): Color? {
        var from = -1
        repeat(occurrence + 1) { from = a.text.indexOf(word, from + 1) }
        check(from >= 0) { "$word not found" }
        return a.spanStyles.lastOrNull { it.start <= from && from < it.end }?.item?.color?.takeIf { it != Color.Unspecified }
    }

    @Test fun `the method is yellow, headers get a name colour, and the text is unchanged`() {
        val msg = "POST /login (https://a.test:443)\nContent-Type: application/json\nX-A: 1\n\n{\"u\":1}"
        val a = HttpHighlight.annotate(msg)
        assertEquals(msg, a.text)
        assertEquals(Method, colorOf(a, "POST"))
        assertEquals(Accent, colorOf(a, "Content-Type"))
        assertEquals(Accent, colorOf(a, "X-A"))
    }

    @Test fun `status codes are green, blue and red`() {
        assertEquals(StatusOk, colorOf(HttpHighlight.annotate("HTTP 200\nA: b\n\nok"), "200"))
        assertEquals(StatusRedirect, colorOf(HttpHighlight.annotate("HTTP 302\n"), "302"))
        assertEquals(StatusError, colorOf(HttpHighlight.annotate("HTTP 404\n"), "404"))
        assertEquals(StatusError, colorOf(HttpHighlight.annotate("HTTP 503\n"), "503"))
    }

    @Test fun `json keys, strings, numbers and literals each have their colour`() {
        val a = HttpHighlight.annotate("HTTP 200\nContent-Type: application/json\n\n{\n  \"name\": \"bunny\",\n  \"n\": -12.5,\n  \"ok\": true,\n  \"x\": null\n}")
        assertEquals(StatusRedirect, colorOf(a, "\"name\""))
        assertEquals(StatusOk, colorOf(a, "\"bunny\""))
        assertEquals(Method, colorOf(a, "-12.5"))
        assertEquals(Accent, colorOf(a, "true"))
        assertEquals(Accent, colorOf(a, "null"))
    }

    @Test fun `html tags attributes and values are coloured and script bodies are left alone`() {
        val a = HttpHighlight.annotate("HTTP 200\n\n<div class=\"a\"><script>var x = 1;</script></div>")
        assertEquals(StatusRedirect, colorOf(a, "div"))
        assertEquals(Method, colorOf(a, "class"))
        assertEquals(StatusOk, colorOf(a, "\"a\""))
        assertNull(colorOf(a, "var x"))
    }

    @Test fun `plain bodies and odd input are left alone and never throw`() {
        HttpHighlight.annotate("")
        HttpHighlight.annotate("\n\n\n")
        HttpHighlight.annotate("(no response recorded)")
        val a = HttpHighlight.annotate("HTTP 200\n\njust some text")
        assertNull(colorOf(a, "just"))
        HttpHighlight.annotate("POST /x\n\n{\"unterminated: [1, 2")
    }

    @Test fun `a huge message is only coloured up to the limit`() {
        val big = "HTTP 200\n\n[" + "1,".repeat(HttpHighlight.MAX_CHARS) + "2]"
        val a = HttpHighlight.annotate(big)
        assertEquals(big, a.text)
        assertTrue(a.spanStyles.all { it.end <= HttpHighlight.MAX_CHARS })
    }

    @Test fun `chunks keep their text and their colours`() {
        val msg = "HTTP 200\nContent-Type: application/json\n\n[" + (1..2000).joinToString(",") { "\"v$it\"" } + "]"
        val chunks = chunkHighlighted(msg, size = 1500)
        assertTrue(chunks.size > 2)
        assertEquals(msg, chunks.joinToString("") { it.text })
        assertEquals(StatusOk, colorOf(chunks[1], "\"v", 0))
    }
}
