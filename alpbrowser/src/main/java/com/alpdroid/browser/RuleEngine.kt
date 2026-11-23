package com.alpdroid.browser

import com.alpdroid.proxy.InterceptedRequest
import com.alpdroid.proxy.InterceptedResponse
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    /** Precompiled regexes, keyed by rule id — compiling on every message was per-request work, and a
     *  hostile `find` could make each rewrite pay the compile (or throw) again. */
    private val compiledRegex = ConcurrentHashMap<String, Regex>()
    /** Rule ids whose `find` does not compile: their rewrite is a no-op instead of a per-message throw. */
    private val invalidRegex = ConcurrentHashMap.newKeySet<String>()

    private fun compile(rule: Rule) {
        compiledRegex.remove(rule.id)
        invalidRegex.remove(rule.id)
        if (!rule.regex || rule.find.isBlank()) return
        runCatching { compiledRegex[rule.id] = Regex(rule.find) }.onFailure { invalidRegex.add(rule.id) }
    }

    companion object {
        /** Hard caps: an unbounded rule list (or a megabyte `find`/`replace`) is a memory / ReDoS vector
         *  from a pasted rules file, and a rule is applied to every message that passes. */
        const val MAX_RULES = 200
        const val MAX_FIND_LENGTH = 4096
        const val MAX_REPLACE_LENGTH = 4096
    }

    /** Called after every change so the owner can persist the list (rules survive a restart). */
    @Volatile var onChange: (() -> Unit)? = null

    fun all(): List<Rule> = rules.toList()
    fun add(rule: Rule) {
        // Blank id (e.g. a rule built without one) gets a UUID — never System.currentTimeMillis(),
        // which collides when two rules are created in the same millisecond and is guessable.
        var r = rule
        if (r.id.isBlank()) r = r.copy(id = UUID.randomUUID().toString())
        r = r.copy(find = r.find.take(MAX_FIND_LENGTH), replace = r.replace.take(MAX_REPLACE_LENGTH))
        val isNew = rules.none { it.id == r.id }
        if (isNew && rules.size >= MAX_RULES) return // at the cap: refuse a new rule rather than grow unbounded
        rules.removeAll { it.id == r.id }
        rules.add(r)
        compile(r)
        onChange?.invoke()
    }
    fun remove(id: String): Boolean = rules.removeAll { it.id == id }.also {
        if (it) { compiledRegex.remove(id); invalidRegex.remove(id); onChange?.invoke() }
    }
    fun clear() { rules.clear(); compiledRegex.clear(); invalidRegex.clear(); onChange?.invoke() }
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val i = rules.indexOfFirst { it.id == id }
        if (i < 0) return false
        rules[i] = rules[i].copy(enabled = enabled)
        onChange?.invoke()
        return true
    }

    fun toJson(): String = org.json.JSONArray().also { arr ->
        rules.forEach { r ->
            arr.put(org.json.JSONObject().put("id", r.id).put("part", r.part.name).put("find", r.find).put("replace", r.replace)
                .put("hostContains", r.hostContains).put("regex", r.regex).put("enabled", r.enabled))
        }
    }.toString()

    /** Replaces the rules with the saved list; unreadable input leaves them untouched. Does not call [onChange]. */
    fun loadJson(text: String) {
        val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return
        val loaded = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val part = runCatching { Part.valueOf(o.optString("part")) }.getOrNull() ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
            Rule(id, part, o.optString("find").take(MAX_FIND_LENGTH), o.optString("replace").take(MAX_REPLACE_LENGTH),
                o.optString("hostContains"), o.optBoolean("regex"), o.optBoolean("enabled", true))
        }.take(MAX_RULES)
        rules.clear(); rules.addAll(loaded)
        compiledRegex.clear(); invalidRegex.clear()
        loaded.forEach(::compile)
    }

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

    /**
     * Dot-boundary host match: a pattern `example.com` matches `example.com` and
     * `sub.example.com`, but NOT `notexample.com` or `example.com.evil.com` — the old
     * `contains` check matched both of those. Blank pattern matches any host.
     */
    private fun hostOk(r: Rule, host: String): Boolean {
        if (r.hostContains.isBlank()) return true
        val pattern = normalizeHost(r.hostContains)
        val h = normalizeHost(host)
        return h == pattern || h.endsWith(".$pattern")
    }

    private fun normalizeHost(host: String): String {
        var h = host.trim().lowercase()
        while (h.endsWith('.')) h = h.dropLast(1)
        return h
    }

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

    private fun rewrite(text: String, r: Rule): String {
        if (!r.regex) return text.replace(r.find, r.replace)
        if (r.id in invalidRegex) return text
        // The regex compiled once at add/load; a rule that arrived any other way compiles lazily here.
        val re = compiledRegex[r.id] ?: runCatching { Regex(r.find) }.getOrNull()?.also { compiledRegex[r.id] = it } ?: return text
        return runCatching { re.replace(text, Regex.escapeReplacement(r.replace)) }.getOrDefault(text)
    }
}
