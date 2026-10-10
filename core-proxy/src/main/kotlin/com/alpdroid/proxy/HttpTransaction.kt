package com.alpdroid.proxy

import java.time.Instant

enum class TrafficSource { BROWSER, TERMINAL }

/**
 * One logged request/response pair (or, for an un-decrypted HTTPS tunnel,
 * one CONNECT event). [opaque] is true for the latter: v0 tunnels HTTPS
 * bytes without MITM-ing them, so there is no header/body to show.
 */
data class HttpTransaction(
    val id: String,
    val source: TrafficSource,
    val timestamp: Instant,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val requestHeaders: List<Pair<String, String>> = emptyList(),
    val requestBody: ByteArray? = null,
    val responseStatus: Int? = null,
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val responseBody: ByteArray? = null,
    val opaque: Boolean = false,
    val durationMs: Long? = null,
) {
    val url: String get() = "$scheme://$host:$port$path"
}
