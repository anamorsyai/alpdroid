package com.alpdroid.browser

import java.util.ArrayDeque

/**
 * A bounded ring of the page's console output (console.log/warn/error, and uncaught JS errors), captured for the
 * web-development workflow so the agent can read what the page is logging without a DevTools client attached. Thread-
 * safe; oldest entries drop once [capacity] is reached.
 */
class ConsoleBuffer(private val capacity: Int = 500) {
    data class Entry(val at: Long, val level: String, val message: String, val source: String, val line: Int)

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun add(level: String, message: String, source: String, line: Int) = synchronized(lock) {
        entries.addLast(Entry(System.currentTimeMillis(), level, message.take(4000), source, line))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** The most recent [limit] entries, oldest first. */
    fun tail(limit: Int): List<Entry> = synchronized(lock) {
        val list = entries.toList()
        if (list.size <= limit) list else list.subList(list.size - limit, list.size)
    }

    fun clear() = synchronized(lock) { entries.clear() }
}
