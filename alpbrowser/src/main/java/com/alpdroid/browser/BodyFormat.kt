package com.alpdroid.browser

import org.json.JSONTokener

/**
 * Makes a captured request/response body readable: JSON is indented, HTML/XML is laid out by nesting, and form posts
 * (`a=1&b=2`) become one `key = value` line each. Display only — nothing here changes what is sent. Anything that is
 * not recognised (or does not parse) is left to the caller as plain text, so a wrong guess can never hide the real bytes.
 */
object BodyFormat {
    private const val MAX_CHARS = 1_500_000

    /** The prettified body, or null when it is empty, binary, in an unknown format, or already as readable as it gets. */
    fun pretty(headers: List<Pair<String, String>>, body: ByteArray?): String? {
        val text = RawHttp.bodyToText(body)?.takeIf { it.isNotBlank() && it.length <= MAX_CHARS } ?: return null
        val ct = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second?.lowercase().orEmpty()
        val t = text.trim()
        val formatted = when {
            ct.contains("json") || t.startsWith("{") || t.startsWith("[") -> json(t)
            ct.contains("x-www-form-urlencoded") -> form(t)
            ct.contains("html") || ct.contains("xml") || ct.contains("svg") || t.startsWith("<") -> markup(t)
            else -> null
        }
        return formatted?.takeIf { it != text }
    }

    /** JSON re-indented with its strings and numbers exactly as written; null if [text] is not one valid JSON value. */
    fun json(text: String): String? {
        val valid = runCatching {
            val tk = JSONTokener(text)
            tk.nextValue()
            tk.nextClean().code == 0
        }.getOrDefault(false)
        if (!valid) return null
        val out = StringBuilder(text.length + text.length / 4)
        var depth = 0
        var inString = false
        var escaped = false
        fun newline() { out.append('\n'); repeat(depth) { out.append("  ") } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                out.append(c)
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> { inString = true; out.append(c) }
                '{', '[' -> {
                    var j = i + 1
                    while (j < text.length && text[j].isWhitespace()) j++
                    val close = if (c == '{') '}' else ']'
                    if (j < text.length && text[j] == close) { out.append(c).append(close); i = j } // empty {} or []
                    else { out.append(c); depth++; newline() }
                }
                '}', ']' -> { depth--; newline(); out.append(c) }
                ',' -> { out.append(','); newline() }
                ':' -> out.append(": ")
                else -> if (!c.isWhitespace()) out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** `a=1&b=x%20y` as `a = 1` / `b = x y`, one per line; null when it does not look like a form post. */
    fun form(text: String): String? {
        val pairs = text.split('&').filter { it.isNotEmpty() }
        if (pairs.isEmpty() || pairs.count { it.contains('=') } * 2 < pairs.size) return null
        fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        return pairs.joinToString("\n") { p ->
            val k = p.substringBefore('=')
            if (p.contains('=')) "${dec(k)} = ${dec(p.substringAfter('='))}" else dec(k)
        }
    }

    private val token = Regex("<!--.*?-->|<!\\[CDATA\\[.*?]]>|<![^>]*>|<\\?.*?\\?>|</?[A-Za-z][^>]*>|[^<]+|<", RegexOption.DOT_MATCHES_ALL)
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val rawTags = setOf("script", "style", "pre", "textarea")

    /** HTML/XML laid out one tag or text run per line, indented by nesting; script/style/pre content stays verbatim. */
    fun markup(text: String): String? {
        if (!text.trimStart().startsWith("<")) return null
        val out = StringBuilder(text.length + text.length / 3)
        var depth = 0
        fun line(s: String) { if (out.isNotEmpty()) out.append('\n'); repeat(minOf(depth, 40)) { out.append("  ") }; out.append(s) }
        val tokens = token.findAll(text).map { it.value }.toList()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.startsWith("</") -> { depth = maxOf(0, depth - 1); line(t) }
                t.startsWith("<!") || t.startsWith("<?") -> line(t)
                t.startsWith("<") && t.length > 1 -> {
                    val name = Regex("^<([A-Za-z][\\w:.-]*)").find(t)?.groupValues?.get(1)?.lowercase().orEmpty()
                    val selfClosing = t.endsWith("/>") || name in voidTags
                    if (!selfClosing && name in rawTags) {
                        // Keep everything up to the matching close tag as one verbatim block.
                        val body = StringBuilder()
                        var j = i + 1
                        while (j < tokens.size && !tokens[j].lowercase().startsWith("</$name")) { body.append(tokens[j]); j++ }
                        line(t)
                        val inner = body.toString().trim('\n', '\r')
                        if (inner.isNotBlank()) { depth++; line(inner.trimIndent().replace("\n", "\n" + "  ".repeat(minOf(depth, 40)))); depth-- }
                        if (j < tokens.size) { line(tokens[j]) }
                        i = j
                    } else {
                        line(t)
                        if (!selfClosing) depth++
                    }
                }
                else -> { val s = t.trim().replace(Regex("\\s+"), " "); if (s.isNotEmpty() && s != "<") line(s) }
            }
            i++
        }
        return out.toString()
    }
}
