package com.alpdroid.browser

import com.alpdroid.proxy.HttpTransaction
import java.time.format.DateTimeFormatter
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Turns captured traffic into files an analyst can keep: raw messages, and a HAR 1.2 archive. */
object Har {
    private fun header(headers: List<Pair<String, String>>, name: String) =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun text(body: ByteArray?): String? = RawHttp.bodyToText(body)

    /** The request as HTTP text: request line, headers, blank line, then the (already decoded) body. */
    fun rawRequest(tx: HttpTransaction): String = buildString {
        append(tx.method).append(' ').append(tx.path.ifEmpty { "/" }).append(" HTTP/1.1\n")
        tx.requestHeaders.forEach { append(it.first).append(": ").append(it.second).append('\n') }
        append('\n')
        append(text(tx.requestBody) ?: if (tx.requestBody == null) "" else "[binary body, ${tx.requestBody!!.size} bytes — see request.body]")
    }

    fun rawResponse(tx: HttpTransaction): String = buildString {
        append("HTTP/1.1 ").append(tx.responseStatus ?: 0).append('\n')
        tx.responseHeaders.forEach { append(it.first).append(": ").append(it.second).append('\n') }
        append('\n')
        append(text(tx.responseBody) ?: if (tx.responseBody == null) "" else "[binary body, ${tx.responseBody!!.size} bytes — see response.body]")
    }

    fun meta(tx: HttpTransaction): JSONObject = JSONObject()
        .put("id", tx.id).put("method", tx.method).put("url", tx.url).put("host", tx.host).put("port", tx.port)
        .put("scheme", tx.scheme).put("path", tx.path).put("status", tx.responseStatus ?: JSONObject.NULL)
        .put("startedAt", tx.timestamp.toString()).put("durationMs", tx.durationMs ?: JSONObject.NULL)
        .put("requestBytes", tx.requestBody?.size ?: 0).put("responseBytes", tx.responseBody?.size ?: 0)
        .put("opaque", tx.opaque)

    private fun headersJson(h: List<Pair<String, String>>) = JSONArray().also { a ->
        h.forEach { a.put(JSONObject().put("name", it.first).put("value", it.second)) }
    }

    private fun queryString(path: String): JSONArray {
        val q = path.substringAfter('?', "")
        val arr = JSONArray()
        if (q.isNotEmpty()) q.split('&').filter { it.isNotEmpty() }.forEach {
            fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
            arr.put(JSONObject().put("name", dec(it.substringBefore('='))).put("value", dec(it.substringAfter('=', ""))))
        }
        return arr
    }

    /** HAR 1.2 for [txs] (CONNECT tunnels are left out: they carry no request or response of their own). */
    fun build(txs: List<HttpTransaction>, version: String): JSONObject {
        val entries = JSONArray()
        for (tx in txs) {
            if (tx.opaque) continue
            val reqMime = header(tx.requestHeaders, "Content-Type") ?: ""
            val resMime = header(tx.responseHeaders, "Content-Type") ?: ""
            val request = JSONObject()
                .put("method", tx.method).put("url", tx.url).put("httpVersion", "HTTP/1.1")
                .put("headers", headersJson(tx.requestHeaders)).put("queryString", queryString(tx.path))
                .put("cookies", JSONArray()).put("headersSize", -1).put("bodySize", tx.requestBody?.size ?: 0)
            tx.requestBody?.let { b ->
                request.put("postData", JSONObject().put("mimeType", reqMime).put("text", text(b) ?: Base64.getEncoder().encodeToString(b)))
            }
            val content = JSONObject().put("size", tx.responseBody?.size ?: 0).put("mimeType", resMime)
            tx.responseBody?.let { b ->
                val t = text(b)
                if (t != null) content.put("text", t) else content.put("text", Base64.getEncoder().encodeToString(b)).put("encoding", "base64")
            }
            val response = JSONObject()
                .put("status", tx.responseStatus ?: 0).put("statusText", "").put("httpVersion", "HTTP/1.1")
                .put("headers", headersJson(tx.responseHeaders)).put("cookies", JSONArray()).put("content", content)
                .put("redirectURL", header(tx.responseHeaders, "Location") ?: "")
                .put("headersSize", -1).put("bodySize", tx.responseBody?.size ?: 0)
            val ms = (tx.durationMs ?: 0L).toInt()
            entries.put(
                JSONObject()
                    .put("startedDateTime", DateTimeFormatter.ISO_INSTANT.format(tx.timestamp))
                    .put("time", ms).put("request", request).put("response", response).put("cache", JSONObject())
                    .put("timings", JSONObject().put("send", 0).put("wait", ms).put("receive", 0)),
            )
        }
        return JSONObject().put(
            "log",
            JSONObject().put("version", "1.2").put("creator", JSONObject().put("name", "AlpBrowser").put("version", version)).put("entries", entries),
        )
    }
}
