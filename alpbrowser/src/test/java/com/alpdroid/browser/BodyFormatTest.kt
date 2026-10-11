package com.alpdroid.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyFormatTest {
    private fun ct(v: String) = listOf("Content-Type" to v)

    @Test fun `json is indented and keeps strings and numbers exactly`() {
        val out = BodyFormat.pretty(ct("application/json"), """{"a":1,"b":[1,2,{"c":"x, y: \"z\""}],"e":{},"f":[],"n":1.50e3}""".toByteArray())!!
        assertEquals(
            "{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2,\n    {\n      \"c\": \"x, y: \\\"z\\\"\"\n    }\n  ],\n  \"e\": {},\n  \"f\": [],\n  \"n\": 1.50e3\n}",
            out,
        )
    }

    @Test fun `json is recognised without a content type and invalid json is left alone`() {
        assertTrue(BodyFormat.pretty(emptyList(), """[1,2]""".toByteArray())!!.contains("\n"))
        assertNull(BodyFormat.pretty(ct("application/json"), """{"a":1,""".toByteArray()))
        assertNull(BodyFormat.pretty(ct("application/json"), """{"a":1} trailing""".toByteArray()))
    }

    @Test fun `already pretty json is not reported as changed`() {
        val once = BodyFormat.json("""{"a":1}""")!!
        assertNull(BodyFormat.pretty(ct("application/json"), once.toByteArray()))
    }

    @Test fun `form posts become one line per field`() {
        assertEquals(
            "custname = Space Bunny\ncustemail = qa@bunny.test\nsize = large",
            BodyFormat.pretty(ct("application/x-www-form-urlencoded"), "custname=Space+Bunny&custemail=qa%40bunny.test&size=large".toByteArray()),
        )
    }

    @Test fun `html is laid_out by nesting and scripts stay verbatim`() {
        val html = "<!DOCTYPE html><html><head><title>Hi</title><script>var a = 1;\nif (a) { b(); }</script></head><body><p>Hello <b>world</b></p><br><img src=x></body></html>"
        val out = BodyFormat.pretty(ct("text/html"), html.toByteArray())!!
        val lines = out.lines()
        assertEquals("<!DOCTYPE html>", lines[0])
        assertEquals("<html>", lines[1])
        assertEquals("  <head>", lines[2])
        assertEquals("    <title>", lines[3])
        assertEquals("      Hi", lines[4])
        assertTrue(out.contains("var a = 1;"))
        assertTrue(out.contains("if (a) { b(); }"))
        assertTrue(lines.contains("      <br>") || lines.any { it.trim() == "<br>" })
        // <br> and <img> are void: the closing </body> lines up with <body>.
        assertEquals(lines.first { it.trim() == "<body>" }.indexOf('<'), lines.first { it.trim() == "</body>" }.indexOf('<'))
    }

    @Test fun `binary and unknown bodies are not touched`() {
        assertNull(BodyFormat.pretty(ct("image/png"), byteArrayOf(0, 1, 2, 3)))
        assertNull(BodyFormat.pretty(ct("text/plain"), "just words".toByteArray()))
        assertNull(BodyFormat.pretty(emptyList(), null))
    }
}
