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

    private val BINARY_TYPES = listOf("image/", "audio/", "video/", "font/", "application/octet-stream", "application/zip", "application/pdf",
        "application/wasm", "application/gzip", "application/x-protobuf", "application/vnd.", "application/x-font")

    /**
     * A body for READING in the Proxy and Repeater views: text whenever it is text. Unlike [bodyToText] (which guards editing and
     * so refuses a body with one stray control byte or one byte that is not valid UTF-8 — which long bodies and pages in another
     * charset nearly always have), this decides from the content type and a sample, decodes with the declared charset or Latin-1
     * when UTF-8 does not fit, and shows a stray control character as "·" instead of calling the whole body binary.
     * Null only for what really is binary.
     */
    fun displayText(headers: List<Pair<String, String>>, body: ByteArray?): String? {
        if (body == null || body.isEmpty()) return ""
        val type = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second?.lowercase().orEmpty()
        if (BINARY_TYPES.any { type.startsWith(it) } && !type.contains("+json") && !type.contains("+xml")) return null
        val n = minOf(body.size, 8192)
        var control = 0
        for (i in 0 until n) { val b = body[i].toInt() and 0xff; if (b == 0 || (b < 32 && b != 9 && b != 10 && b != 13 && b != 12 && b != 27)) control++ }
        if (control * 100 > n) return null // more than 1 % control bytes in the sample: not text
        val declared = Regex("charset=\"?([A-Za-z0-9_.:-]+)").find(type)?.groupValues?.get(1)
        val strict = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { null }
        val text = strict ?: String(body, runCatching { java.nio.charset.Charset.forName(declared ?: "windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        return if (text.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) text.map { if (it < ' ' && it != '\n' && it != '\r' && it != '\t') '·' else it }.joinToString("") else text
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
