package com.alpdroid.browser

/**
 * Text <-> HTTP conversions shared by the Proxy and Repeater screens, kept free of Android types so they are
 * unit-tested: the editors show headers as "Name: value" lines and bodies as text, and these turn that text back
 * into exactly what goes on the wire.
 */
object RawHttp {
    fun headersToText(headers: List<Pair<String, String>>): String =
        headers.joinToString("\n") { "${it.first}: ${it.second}" }

    /** Lines without a colon, or with an empty name, are ignored; a header value keeps any further colons. */
    fun parseHeaders(text: String): List<Pair<String, String>> =
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().takeIf { it.isNotEmpty() && it.none { c -> c <= ' ' } }
                ?.let { it to line.substring(i + 1).trim() }
        }.toList()

    /** The body as editable text, or null when it is binary (editing it as text would corrupt it). */
    fun bodyToText(body: ByteArray?): String? {
        if (body == null || body.isEmpty()) return ""
        val s = String(body, Charsets.UTF_8)
        if (s.contains('�')) return null
        if (s.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) return null
        return s
    }

    fun textToBody(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** "https://host:443/p" -> "https://host/p": default ports are dropped so the URL reads (and sends) naturally. */
    fun displayUrl(scheme: String, host: String, port: Int, path: String): String {
        val defaultPort = if (scheme == "https") 443 else 80
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://$h${if (port == defaultPort) "" else ":$port"}${path.ifEmpty { "/" }}"
    }

    /** Splits a repeater URL into its pieces, or null when it is not an http(s) URL with a host. */
    fun splitUrl(url: String): Triple<String, String, String>? { // (scheme, hostPort, pathAndQuery)
        val m = Regex("^(https?)://([^/?#]+)([/?#].*)?$", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
        return Triple(m.groupValues[1].lowercase(), m.groupValues[2], m.groupValues[3].ifEmpty { "/" })
    }
}
