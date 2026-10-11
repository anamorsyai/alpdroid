package com.alpdroid.browser.ui.proxy

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.alpdroid.browser.ui.theme.Accent
import com.alpdroid.browser.ui.theme.Method
import com.alpdroid.browser.ui.theme.StatusError
import com.alpdroid.browser.ui.theme.StatusOk
import com.alpdroid.browser.ui.theme.StatusRedirect
import com.alpdroid.browser.ui.theme.TextMain
import com.alpdroid.browser.ui.theme.TextMuted

/**
 * Colours for a shown HTTP message, using the app's palette: the method in yellow, status codes green / blue / red,
 * header names in the accent colour, and JSON or HTML in the usual code colours (keys and tags blue, strings green,
 * numbers yellow, true/false/null in the accent). Display only: the text itself is never changed. Very large messages are
 * coloured only up to [MAX_CHARS], so painting can never be what freezes the screen.
 */
object HttpHighlight {
    const val MAX_CHARS = 400_000

    private val methodStyle = SpanStyle(color = Method, fontWeight = FontWeight.SemiBold)
    private val nameStyle = SpanStyle(color = Accent)
    private val valueStyle = SpanStyle(color = TextMain)
    private val mutedStyle = SpanStyle(color = TextMuted)
    private val keyStyle = SpanStyle(color = StatusRedirect)
    private val stringStyle = SpanStyle(color = StatusOk)
    private val numberStyle = SpanStyle(color = Method)
    private val literalStyle = SpanStyle(color = Accent)

    fun statusColor(code: Int): Color = when (code) {
        in 100..399 -> if (code in 200..299) StatusOk else StatusRedirect
        else -> StatusError
    }

    fun annotate(text: String): AnnotatedString {
        val limit = minOf(text.length, MAX_CHARS)
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        fun span(style: SpanStyle, start: Int, end: Int) { if (end > start && end <= limit) spans.add(AnnotatedString.Range(style, start, end)) }

        // 1. The first line, then headers up to the first blank line, then the body.
        var pos = 0
        var lineNo = 0
        var inHeaders = true
        var bodyStart = limit
        while (pos < limit) {
            var eol = text.indexOf('\n', pos)
            if (eol < 0 || eol > limit) eol = limit
            val line = text.substring(pos, eol)
            if (lineNo == 0) firstLine(line, pos, ::span)
            else if (line.isBlank()) { bodyStart = minOf(eol + 1, limit); inHeaders = false }
            else if (inHeaders) {
                val colon = line.indexOf(':')
                if (colon > 0) { span(nameStyle, pos, pos + colon); span(mutedStyle, pos + colon, pos + colon + 1); span(valueStyle, pos + colon + 1, eol) }
            }
            if (!inHeaders) break
            pos = eol + 1
            lineNo++
        }
        if (bodyStart < limit) body(text, bodyStart, limit, ::span)
        return AnnotatedString(text, spans)
    }

    private fun firstLine(line: String, at: Int, span: (SpanStyle, Int, Int) -> Unit) {
        if (line.startsWith("HTTP")) {
            val sp = line.indexOf(' ')
            span(mutedStyle, at, if (sp < 0) at + line.length else at + sp)
            if (sp > 0) {
                var e = sp + 1
                while (e < line.length && line[e].isDigit()) e++
                val code = line.substring(sp + 1, e).toIntOrNull()
                if (code != null) span(SpanStyle(color = statusColor(code), fontWeight = FontWeight.SemiBold), at + sp + 1, at + e)
            }
            return
        }
        val firstSp = line.indexOf(' ')
        if (firstSp in 2..8 && line.substring(0, firstSp).all { it.isLetter() && it.isUpperCase() }) {
            span(methodStyle, at, at + firstSp)
            val paren = line.indexOf(" (", firstSp)
            if (paren > 0) span(mutedStyle, at + paren, at + line.length)
        }
    }

    private fun body(text: String, start: Int, end: Int, span: (SpanStyle, Int, Int) -> Unit) {
        var i = start
        while (i < end && text[i].isWhitespace()) i++
        if (i >= end) return
        when (text[i]) {
            '{', '[' -> json(text, start, end, span)
            '<' -> markup(text, start, end, span)
        }
    }

    private fun json(t: String, start: Int, end: Int, span: (SpanStyle, Int, Int) -> Unit) {
        var i = start
        while (i < end) {
            val c = t[i]
            when {
                c == '"' -> {
                    var j = i + 1
                    while (j < end && t[j] != '"' && t[j] != '\n') { if (t[j] == '\\') j++; j++ }
                    val close = minOf(j + 1, end)
                    var k = close
                    while (k < end && (t[k] == ' ' || t[k] == '\t')) k++
                    span(if (k < end && t[k] == ':') keyStyle else stringStyle, i, close)
                    i = close
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < end && (t[j].isDigit() || t[j] == '.' || t[j] == 'e' || t[j] == 'E' || t[j] == '+' || t[j] == '-')) j++
                    span(numberStyle, i, j); i = j
                }
                t.startsWith("true", i) || t.startsWith("null", i) || t.startsWith("false", i) -> {
                    val n = if (t.startsWith("false", i)) 5 else 4
                    span(literalStyle, i, minOf(i + n, end)); i += n
                }
                c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':' -> { span(mutedStyle, i, i + 1); i++ }
                else -> i++
            }
        }
    }

    private fun markup(t: String, start: Int, end: Int, span: (SpanStyle, Int, Int) -> Unit) {
        var i = start
        while (i < end) {
            if (t[i] != '<') { i++; continue }
            if (t.startsWith("<!--", i)) {
                val close = t.indexOf("-->", i + 4).let { if (it < 0 || it + 3 > end) end else it + 3 }
                span(mutedStyle, i, close); i = close; continue
            }
            // A tag: <name attr="value" ...> or </name>
            var j = i + 1
            if (j < end && (t[j] == '/' || t[j] == '!' || t[j] == '?')) j++
            val nameStart = j
            while (j < end && !t[j].isWhitespace() && t[j] != '>' && t[j] != '/') j++
            span(mutedStyle, i, nameStart); span(keyStyle, nameStart, j)
            while (j < end && t[j] != '>') {
                when {
                    t[j] == '"' || t[j] == '\'' -> {
                        val q = t[j]
                        var e = j + 1
                        while (e < end && t[e] != q) e++
                        e = minOf(e + 1, end)
                        span(stringStyle, j, e); j = e
                    }
                    t[j].isLetter() -> {
                        var e = j
                        while (e < end && (t[e].isLetterOrDigit() || t[e] == '-' || t[e] == ':' || t[e] == '_')) e++
                        span(numberStyle, j, e); j = e
                    }
                    else -> j++
                }
            }
            if (j < end) { span(mutedStyle, j, j + 1); j++ }
            val tag = t.substring(nameStart, minOf(nameStart + 6, end)).lowercase()
            if ((tag.startsWith("script") || tag.startsWith("style")) && !t.startsWith("</", i)) {
                val closeTag = t.indexOf("</", j).let { if (it < 0 || it > end) end else it }
                i = closeTag   // the code inside is left uncoloured
            } else i = j
        }
    }
}
