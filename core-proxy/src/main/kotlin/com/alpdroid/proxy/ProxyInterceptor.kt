package com.alpdroid.proxy

/**
 * A live hook the proxy calls for every decrypted message, so a controller can pause it, modify it, or drop it — the
 * Burp-style "intercept" / breakpoint that automated tooling (and an agent) drives. Both methods are `suspend`:
 * returning is what releases the message, so an implementation may hold it indefinitely until a decision arrives.
 *
 * It is invoked only for traffic the proxy actually decrypts (plain HTTP and MITM-ed HTTPS), never for an opaque
 * tunnel. [ProxyEngine] runs with no interceptor by default, in which case the relay is byte-for-byte unchanged.
 */
interface ProxyInterceptor {
    suspend fun onRequest(req: InterceptedRequest): RequestAction
    suspend fun onResponse(res: InterceptedResponse): ResponseAction
}

/** The request as read from the client, before it is forwarded upstream. [id] ties the request to its later response. */
data class InterceptedRequest(
    val id: String,
    val source: TrafficSource,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    /** The request target (path + query), e.g. "/a?b=1". */
    val target: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
)

/** The response as read from upstream, before it is written back to the client. */
data class InterceptedResponse(
    val id: String,
    val source: TrafficSource,
    val host: String,
    val port: Int,
    /** The HTTP status code, or null when the status line could not be parsed. */
    val status: Int?,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
)

/** What to do with an intercepted request. */
sealed interface RequestAction {
    /** Forward it exactly as received. */
    data object Forward : RequestAction

    /**
     * Forward a modified request. Any field left null keeps the original. When [body] is given (non-null, including an
     * empty array) the engine rewrites Content-Length to match it, so the controller never has to.
     */
    data class Replace(
        val method: String? = null,
        val target: String? = null,
        val headers: List<Pair<String, String>>? = null,
        val body: ByteArray? = null,
    ) : RequestAction

    /** Do not forward it: the client connection is closed and the transaction is recorded as dropped. */
    data object Drop : RequestAction
}

/** What to do with an intercepted response. */
sealed interface ResponseAction {
    /** Write it back to the client exactly as received. */
    data object Forward : ResponseAction

    /**
     * Write a modified response back. Any field left null keeps the original; [body] non-null rewrites Content-Length.
     * [status] replaces only the numeric code (the reason phrase is kept).
     */
    data class Replace(
        val status: Int? = null,
        val headers: List<Pair<String, String>>? = null,
        val body: ByteArray? = null,
    ) : ResponseAction

    /** Do not write anything back: the client connection is closed. */
    data object Drop : ResponseAction
}
