package com.alpdroid.proxy

import java.io.InputStream
import java.io.OutputStream

internal data class RequestLine(val method: String, val target: String, val version: String)

internal data class Target(val scheme: String, val host: String, val port: Int, val path: String)

/** Reads one CRLF- or LF-terminated line as US-ASCII text, or null at EOF before any bytes. */
internal fun readLine(input: InputStream): String? {
    val buffer = ByteArrayBuilder()
    var sawAnyByte = false
    while (true) {
        val b = input.read()
        if (b == -1) return if (sawAnyByte) buffer.toAsciiString() else null
        sawAnyByte = true
        if (b == '\n'.code) {
            return buffer.toAsciiString().removeSuffix("\r")
        }
        buffer.append(b)
    }
}

internal fun readHeaders(input: InputStream): List<Pair<String, String>> {
    val headers = mutableListOf<Pair<String, String>>()
    while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx == -1) continue
        headers += line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }
    return headers
}

internal fun parseRequestLine(line: String): RequestLine? {
    val parts = line.split(' ')
    if (parts.size != 3) return null
    return RequestLine(parts[0], parts[1], parts[2])
}

/**
 * Splits a proxy request target into scheme/host/port/path.
 * - `CONNECT host:port` (used for tunnelling HTTPS)
 * - `GET http://host:port/path HTTP/1.1` (absolute-URI form a proxy receives)
 */
internal fun parseTarget(method: String, raw: String): Target? {
    if (method.equals("CONNECT", ignoreCase = true)) {
        val idx = raw.lastIndexOf(':')
        if (idx == -1) return null
        val host = raw.substring(0, idx)
        val port = raw.substring(idx + 1).toIntOrNull() ?: return null
        return Target("https", host, port, "")
    }

    val schemeEnd = raw.indexOf("://")
    if (schemeEnd == -1) return null
    val scheme = raw.substring(0, schemeEnd)
    val rest = raw.substring(schemeEnd + 3)
    val pathStart = rest.indexOf('/')
    val authority = if (pathStart == -1) rest else rest.substring(0, pathStart)
    val path = if (pathStart == -1) "/" else rest.substring(pathStart)
    val colonIdx = authority.lastIndexOf(':')
    val host: String
    val port: Int
    if (colonIdx == -1) {
        host = authority
        port = if (scheme == "https") 443 else 80
    } else {
        host = authority.substring(0, colonIdx)
        port = authority.substring(colonIdx + 1).toIntOrNull() ?: if (scheme == "https") 443 else 80
    }
    return Target(scheme, host, port, path)
}

internal fun headerValue(headers: List<Pair<String, String>>, name: String): String? =
    headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

/** Replaces (or adds) Content-Length to match [bodyLen] and drops any Transfer-Encoding — a fixed-length body and
 *  chunked encoding are mutually exclusive, so an edited body must carry an accurate length and no stale framing. */
internal fun withContentLength(headers: List<Pair<String, String>>, bodyLen: Int): List<Pair<String, String>> =
    headers.filterNot { it.first.equals("Content-Length", ignoreCase = true) || it.first.equals("Transfer-Encoding", ignoreCase = true) } +
        ("Content-Length" to bodyLen.toString())

/** Rewrites the numeric status code in an HTTP status line, keeping the version and reason phrase ("HTTP/1.1 200 OK"
 *  -> "HTTP/1.1 404 OK"). Returns a best-effort line when the original could not be parsed. */
internal fun replaceStatusCode(statusLine: String?, code: Int): String {
    val parts = statusLine?.split(' ', limit = 3) ?: return "HTTP/1.1 $code"
    if (parts.size < 2) return "HTTP/1.1 $code"
    val reason = parts.getOrNull(2) ?: ""
    return "${parts[0]} $code $reason".trimEnd()
}

internal fun writeRequestLine(output: OutputStream, method: String, path: String, version: String) {
    output.write("$method $path $version\r\n".toByteArray(Charsets.US_ASCII))
}

internal fun writeHeaders(output: OutputStream, headers: List<Pair<String, String>>) {
    for ((name, value) in headers) {
        output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
    }
    output.write("\r\n".toByteArray(Charsets.US_ASCII))
}

/** Small growable byte buffer; avoids pulling in a dependency just to build an ASCII line. */
private class ByteArrayBuilder {
    private var bytes = ByteArray(64)
    private var size = 0

    fun append(b: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
        bytes[size++] = b.toByte()
    }

    fun toAsciiString(): String = String(bytes, 0, size, Charsets.US_ASCII)
}
