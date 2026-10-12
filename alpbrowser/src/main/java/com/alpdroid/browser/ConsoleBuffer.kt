package com.alpdroid.browser

import java.util.ArrayDeque

/**
 * A bounded ring of the page's console output (console.log/warn/error, and uncaught JS errors), captured for the
 * web-development workflow so the agent can read what the page is logging without a DevTools client attached. Thread-
 * safe; oldest entries drop once [capacity] is reached.
 */
class ConsoleBuffer(private val capacity: Int = 500) {
    data class Entry(val at: Long, val level: String, val message: String, val source: String, val line: Int, val tab: Int = 0)

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun add(level: String, message: String, source: String, line: Int, tab: Int = 0) = synchronized(lock) {
        entries.addLast(Entry(System.currentTimeMillis(), level, message.take(4000), source, line, tab))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** The most recent [limit] entries, oldest first. */
    fun tail(limit: Int, tab: Int? = null): List<Entry> = synchronized(lock) {
        val list = if (tab == null) entries.toList() else entries.filter { it.tab == tab }
        if (list.size <= limit) list else list.subList(list.size - limit, list.size)
    }

    /** Empties the buffer, or only [tab]'s lines, so one agent clearing its log does not wipe another's. */
    fun clear(tab: Int? = null) = synchronized(lock) { if (tab == null) entries.clear() else entries.removeAll { it.tab == tab } }
}
