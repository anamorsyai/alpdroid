package com.alpdroid.proxy

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Decides which hosts are "in scope". Used by the terminal engine to keep
 * background noise (package-manager checks, unrelated tool traffic) out of
 * History: an empty pattern list means "everything matches" (used while a
 * hunter hasn't picked a target yet), a non-empty list means only matching
 * hosts get recorded.
 */
class ScopeFilter {
    private val patterns = CopyOnWriteArrayList<Regex>()

    fun setPatterns(hostGlobs: List<String>) {
        patterns.clear()
        patterns.addAll(hostGlobs.map(::globToRegex))
    }

    fun matches(host: String): Boolean {
        if (patterns.isEmpty()) return true
        return patterns.any { it.matches(host) }
    }

    private companion object {
        fun globToRegex(glob: String): Regex {
            val pattern = glob.split("*").joinToString(".*") { Regex.escape(it) }
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }
    }
}
