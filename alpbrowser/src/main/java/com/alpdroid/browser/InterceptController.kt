package com.alpdroid.browser

import com.alpdroid.proxy.InterceptedRequest
import com.alpdroid.proxy.InterceptedResponse
import com.alpdroid.proxy.ProxyInterceptor
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

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
    @Volatile var enabled = false
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

    val interceptor: ProxyInterceptor = object : ProxyInterceptor {
        override suspend fun onRequest(req: InterceptedRequest): RequestAction {
            val ruleEdit = rules.applyRequest(req) // automatic match-and-replace (may be null)
            val ruleAction: RequestAction = ruleEdit ?: RequestAction.Forward
            if (!enabled || !matches(req.host, req.method)) return ruleAction
            // Breakpoint on: park the rule-applied view so the agent sees exactly what would be sent.
            val effHeaders = ruleEdit?.headers ?: req.headers
            val effBody = if (ruleEdit != null && ruleEdit.body != null) ruleEdit.body else req.body
            val holdId = "h${seq.getAndIncrement()}"
            val d = CompletableDeferred<RequestAction>()
            reqHolds[holdId] = d
            info[holdId] = Held(holdId, "request", req.id, req.method, req.host, req.port, req.target, null, effHeaders, effBody)
            return try {
                // "Forward" from the agent means "send the rule-applied version", not the untouched original.
                when (val chosen = d.await()) { RequestAction.Forward -> ruleAction; else -> chosen }
            } finally { reqHolds.remove(holdId); info.remove(holdId) }
        }

        override suspend fun onResponse(res: InterceptedResponse): ResponseAction {
            val ruleEdit = rules.applyResponse(res)
            val ruleAction: ResponseAction = ruleEdit ?: ResponseAction.Forward
            if (!enabled || !matches(res.host, null)) return ruleAction
            val effHeaders = ruleEdit?.headers ?: res.headers
            val effBody = if (ruleEdit != null && ruleEdit.body != null) ruleEdit.body else res.body
            val holdId = "h${seq.getAndIncrement()}"
            val d = CompletableDeferred<ResponseAction>()
            resHolds[holdId] = d
            info[holdId] = Held(holdId, "response", res.id, "", res.host, res.port, "", res.status, effHeaders, effBody)
            return try {
                when (val chosen = d.await()) { ResponseAction.Forward -> ruleAction; else -> chosen }
            } finally { resHolds.remove(holdId); info.remove(holdId) }
        }
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
}
