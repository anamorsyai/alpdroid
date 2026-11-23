package com.alpdroid.proxy

import java.net.IDN
import java.net.InetAddress

/**
 * Decides which hosts are "in scope". Used by the terminal engine to keep
 * background noise (package-manager checks, unrelated tool traffic) out of
 * History: an empty pattern list means "everything matches" (used while a
 * hunter hasn't picked a target yet), a non-empty list means only matching
 * hosts get recorded.
 *
 * Hosts are normalised before matching (trim, trailing dot, lowercase, IDN,
 * canonical IP) on both sides, so `EXAMPLE.com.`, `exämple.com` and
 * `127.000.0.1` match the same pattern as their canonical forms.
 */
class ScopeFilter {
    // One volatile reference, swapped whole: setPatterns() used to clear() then addAll()
    // on a shared list, and a concurrent matches() in between saw an empty list — which
    // means "everything matches" — and recorded out-of-scope traffic.
    @Volatile private var patterns: List<Regex> = emptyList()

    fun setPatterns(hostGlobs: List<String>) {
        patterns = hostGlobs.map(::globToRegex)
    }

    fun matches(host: String): Boolean {
        val current = patterns
        if (current.isEmpty()) return true
        return current.any { it.matches(normalizeHost(host)) }
    }

    /** Trim, strip a trailing dot and brackets, lowercase, IDN-encode, canonicalise IP literals. */
    internal fun normalizeHost(host: String): String {
        var h = host.trim()
        while (h.endsWith('.')) h = h.dropLast(1)
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        h = h.lowercase()
        if (h.isEmpty()) return h
        // Canonicalise IP literals only (never resolve a name: getByName would do DNS).
        if (h.all { it.isDigit() || it == '.' } || ':' in h) {
            runCatching { InetAddress.getByName(h).hostAddress }.getOrNull()?.let { return it }
        }
        return runCatching { IDN.toASCII(h) }.getOrDefault(h)
    }

    private companion object {
        fun globToRegex(glob: String): Regex {
            // Normalise the pattern the same way as a host, label by label so a `*`
            // wildcard (and a `*` inside a label) survives, and a trailing dot / case
            // difference in the pattern cannot make a match miss.
            var clean = glob.trim().removeSuffix(".").lowercase()
            // An IP-literal pattern canonicalises like a host (`127.000.0.1` -> `127.0.0.1`).
            if ('*' !in clean && (clean.all { it.isDigit() || it == '.' } || ':' in clean)) {
                runCatching { InetAddress.getByName(clean).hostAddress }.getOrNull()?.let { clean = it }
            }
            val pattern = clean.split("*").joinToString(".*") { part ->
                // IDN-encode each dot-separated label of the literal part.
                part.split(".").joinToString(".") { label ->
                    Regex.escape(runCatching { IDN.toASCII(label) }.getOrDefault(label))
                }
            }
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }
    }
}
