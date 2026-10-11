package com.alpdroid.browser

import com.alpdroid.proxy.InterceptedRequest
import com.alpdroid.proxy.InterceptedResponse
import com.alpdroid.proxy.ProxyInterceptor
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Burp-style breakpoint, driven by the control API (and therefore by the agent). While [enabled] is off, every
 * message is forwarded untouched (traffic is still captured into History). While on, each matching message is PARKED
 * — [ProxyInterceptor]'s suspend call blocks — and surfaced in [pending] until the controller resolves it with
 * forward / replace / drop. A message that does not match the scope is forwarded without being held.
 *
 * Thread-safe: the proxy calls [interceptor] from many IO coroutines; the control API calls [pending]/[resolve*] from
 * its own threads. State is in concurrent maps + volatiles, and each hold is released through its own CompletableDeferred.
 */
class InterceptController {
    /** Match-and-replace rules applied automatically to all traffic (bug-bounty workflow), before any breakpoint. */
    val rules = RuleEngine()
    /** Master switch: hold matching REQUESTS. Switching it also tells the app to route the page through the proxy. */
    @Volatile var enabled = false
        set(value) { if (field != value) { field = value; onEnabledChange?.invoke(value) } }
    @Volatile var onEnabledChange: ((Boolean) -> Unit)? = null
    /** Also hold RESPONSES (as in Burp, off by default: a request and its response would otherwise both stop every page). */
    @Volatile var interceptResponses = false
    /** Never hold images, stylesheets, scripts and fonts — they are most of a page's traffic and rarely interesting. */
    @Volatile var skipStatic = true
    /** Only hold messages whose host contains this (case-insensitive). Blank = any host. */
    @Volatile var hostFilter: String = ""
    /** Only hold messages whose method equals this (case-insensitive). Blank = any method. */
    @Volatile var methodFilter: String = ""

    /** A parked message awaiting a decision, as the API exposes it. */
    data class Held(
        val holdId: String,
        val kind: String, // "request" or "response"
        val txId: String,
        val method: String,
        val host: String,
        val port: Int,
        val target: String,
        val status: Int?,
        val headers: List<Pair<String, String>>,
        val body: ByteArray?,
    )

    private val seq = AtomicLong(1)
    private val reqHolds = ConcurrentHashMap<String, CompletableDeferred<RequestAction>>()
    private val resHolds = ConcurrentHashMap<String, CompletableDeferred<ResponseAction>>()
    private val info = ConcurrentHashMap<String, Held>()

    private val _pendingFlow = MutableStateFlow<List<Held>>(emptyList())
    /** [pending] as an observable, so the Proxy screen updates the moment a message is parked or released. */
    val pendingFlow: StateFlow<List<Held>> = _pendingFlow.asStateFlow()
    private fun publish() { _pendingFlow.value = pending() }

    val interceptor: ProxyInterceptor = object : ProxyInterceptor {
        override suspend fun onRequest(req: InterceptedRequest): RequestAction {
            val ruleEdit = rules.applyRequest(req) // automatic match-and-replace (may be null)
            val ruleAction: RequestAction = ruleEdit ?: RequestAction.Forward
            val isStatic = skipStatic && looksStatic(req.target)
            if (isStatic && interceptResponses && enabled) { if (staticIds.size > 4000) staticIds.clear(); staticIds.add(req.id) }
            if (!enabled || isStatic || !matches(req.host, req.method)) return ruleAction
            // Breakpoint on: park the rule-applied view so the agent sees exactly what would be sent.
            val effHeaders = ruleEdit?.headers ?: req.headers
            val effBody = if (ruleEdit != null && ruleEdit.body != null) ruleEdit.body else req.body
            val holdId = "h${seq.getAndIncrement()}"
            val d = CompletableDeferred<RequestAction>()
            reqHolds[holdId] = d
            info[holdId] = Held(holdId, "request", req.id, req.method, req.host, req.port, req.target, null, effHeaders, effBody)
            publish()
            // Race guard: if interception was switched off (setIntercept flips `enabled` then calls
            // releaseAll) in the window between the check above and registering this hold, releaseAll's
            // snapshot may have missed us — forward now instead of parking this request forever.
            if (!enabled) { reqHolds.remove(holdId); info.remove(holdId); publish(); return ruleAction }
            return try {
                // "Forward" from the agent means "send the rule-applied version", not the untouched original.
                when (val chosen = d.await()) { RequestAction.Forward -> ruleAction; else -> chosen }
            } finally { reqHolds.remove(holdId); info.remove(holdId); publish() }
        }

        override suspend fun onResponse(res: InterceptedResponse): ResponseAction {
            val ruleEdit = rules.applyResponse(res)
            val ruleAction: ResponseAction = ruleEdit ?: ResponseAction.Forward
            val requestWasStatic = staticIds.remove(res.id)
            if (!enabled || !interceptResponses || requestWasStatic || (skipStatic && staticContentType(res.headers)) || !matches(res.host, null)) return ruleAction
            val effHeaders = ruleEdit?.headers ?: res.headers
            val effBody = if (ruleEdit != null && ruleEdit.body != null) ruleEdit.body else res.body
            val holdId = "h${seq.getAndIncrement()}"
            val d = CompletableDeferred<ResponseAction>()
            resHolds[holdId] = d
            info[holdId] = Held(holdId, "response", res.id, "", res.host, res.port, "", res.status, effHeaders, effBody)
            publish()
            // Same race guard as onRequest: never leave a response parked after interception is off.
            if (!enabled) { resHolds.remove(holdId); info.remove(holdId); publish(); return ruleAction }
            return try {
                when (val chosen = d.await()) { ResponseAction.Forward -> ruleAction; else -> chosen }
            } finally { resHolds.remove(holdId); info.remove(holdId); publish() }
        }
    }

    private val staticIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun looksStatic(target: String): Boolean {
        val path = target.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        return ext in STATIC_EXTENSIONS
    }

    private fun staticContentType(headers: List<Pair<String, String>>): Boolean {
        val ct = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second?.lowercase() ?: return false
        return ct.startsWith("image/") || ct.startsWith("font/") || ct.startsWith("text/css") || ct.contains("javascript") ||
            ct.startsWith("audio/") || ct.startsWith("video/") || ct.contains("font")
    }

    private fun matches(host: String, method: String?): Boolean {
        if (hostFilter.isNotBlank() && !host.contains(hostFilter, ignoreCase = true)) return false
        if (methodFilter.isNotBlank() && method != null && !method.equals(methodFilter, ignoreCase = true)) return false
        return true
    }

    /** Everything currently parked, oldest first. */
    fun pending(): List<Held> = info.values.sortedBy { it.holdId.drop(1).toLongOrNull() ?: 0L }

    fun get(holdId: String): Held? = info[holdId]

    /** Resolves a held request. Returns false when [holdId] is not a currently-parked request. */
    fun resolveRequest(holdId: String, action: RequestAction): Boolean = reqHolds[holdId]?.complete(action) ?: false

    /** Resolves a held response. Returns false when [holdId] is not a currently-parked response. */
    fun resolveResponse(holdId: String, action: ResponseAction): Boolean = resHolds[holdId]?.complete(action) ?: false

    /** Forwards every parked message unchanged — used when interception is turned off so nothing stays stuck. */
    fun releaseAll() {
        reqHolds.values.toList().forEach { it.complete(RequestAction.Forward) }
        resHolds.values.toList().forEach { it.complete(ResponseAction.Forward) }
    }

    private companion object {
        val STATIC_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "css", "js", "mjs", "map",
            "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "webm", "ogg", "wav",
        )
    }
}
