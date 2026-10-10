package com.alpdroid.browser

import com.alpdroid.proxy.InterceptedRequest
import com.alpdroid.proxy.InterceptedResponse
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Match-and-replace rules — the bug-bounty workhorse: rewrite matching traffic automatically, with no manual
 * breakpoint. Each rule names a part (a request/response header or body), a host scope, a `find` (plain substring or,
 * when [regex] is set, a regular expression) and a `replace`. A header rule adds the header when it is absent and
 * [find] is blank; a body rule rewrites every occurrence. Rules apply in order; several can touch one message.
 *
 * Pure and thread-safe (a copy-on-write list), so it is unit-tested and the proxy's IO coroutines use it lock-free.
 */
class RuleEngine {
    enum class Part { REQ_HEADER, REQ_BODY, RES_HEADER, RES_BODY }

    data class Rule(
        val id: String,
        val part: Part,
        val find: String,
        val replace: String,
        val hostContains: String = "",
        val regex: Boolean = false,
        val enabled: Boolean = true,
    )

    private val rules = CopyOnWriteArrayList<Rule>()

    fun all(): List<Rule> = rules.toList()
    fun add(rule: Rule) { rules.removeAll { it.id == rule.id }; rules.add(rule) }
    fun remove(id: String): Boolean = rules.removeAll { it.id == id }
    fun clear() = rules.clear()

    /** The request edits the rules call for, or null when nothing matches (forward unchanged). */
    fun applyRequest(req: InterceptedRequest): RequestAction.Replace? {
        var headers = req.headers
        var body = req.body
        var touched = false
        for (r in rules) {
            if (!r.enabled || !hostOk(r, req.host)) continue
            when (r.part) {
                Part.REQ_HEADER -> { val h = applyHeader(headers, r); if (h !== headers) { headers = h; touched = true } }
                Part.REQ_BODY -> { val b = applyBody(body, r); if (b != null) { body = b; touched = true } }
                else -> {}
            }
        }
        return if (touched) RequestAction.Replace(headers = headers, body = body) else null
    }

    /** The response edits the rules call for, or null when nothing matches. */
    fun applyResponse(res: InterceptedResponse): ResponseAction.Replace? {
        var headers = res.headers
        var body = res.body
        var touched = false
        for (r in rules) {
            if (!r.enabled || !hostOk(r, res.host)) continue
            when (r.part) {
                Part.RES_HEADER -> { val h = applyHeader(headers, r); if (h !== headers) { headers = h; touched = true } }
                Part.RES_BODY -> { val b = applyBody(body, r); if (b != null) { body = b; touched = true } }
                else -> {}
            }
        }
        return if (touched) ResponseAction.Replace(headers = headers, body = body) else null
    }

    private fun hostOk(r: Rule, host: String) = r.hostContains.isBlank() || host.contains(r.hostContains, ignoreCase = true)

    /** A header rule: blank [find] sets/overwrites the named header ([replace] = "Name: value" or just the value for the
     *  header named by [find] when given); otherwise it rewrites header VALUES whose text matches. */
    private fun applyHeader(headers: List<Pair<String, String>>, r: Rule): List<Pair<String, String>> {
        if (r.find.isBlank()) {
            // Add/replace a header. `replace` is "Name: value".
            val colon = r.replace.indexOf(':')
            if (colon <= 0) return headers
            val name = r.replace.substring(0, colon).trim()
            val value = r.replace.substring(colon + 1).trim()
            val without = headers.filterNot { it.first.equals(name, ignoreCase = true) }
            return without + (name to value)
        }
        var changed = false
        val out = headers.map { (n, v) ->
            val nv = rewrite(v, r)
            if (nv != v) changed = true
            n to nv
        }
        return if (changed) out else headers
    }

    private fun applyBody(body: ByteArray?, r: Rule): ByteArray? {
        if (body == null || body.isEmpty()) return null
        val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return null
        val nv = rewrite(text, r)
        return if (nv != text) nv.toByteArray(Charsets.UTF_8) else null
    }

    private fun rewrite(text: String, r: Rule): String =
        if (r.regex) runCatching { Regex(r.find).replace(text, Regex.escapeReplacement(r.replace)) }.getOrDefault(text)
        else text.replace(r.find, r.replace)
}
