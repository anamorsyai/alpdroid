package com.alpdroid.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory History for the current session. Shared by the browser and
 * terminal engines. Capped at [maxSize]: a live browsing session can
 * generate a transaction per subresource, and `it + tx` on an unbounded
 * list means both a full-list copy on every single record() and unbounded
 * memory growth the longer the app stays open. Oldest entries drop first.
 */
class TransactionStore(private val maxSize: Int = 500, private val maxBytes: Long = 64L * 1024 * 1024) {
    private val _transactions = MutableStateFlow<List<HttpTransaction>>(emptyList())

    /** The frames of WebSocket connections and the events of SSE streams seen through the proxy, by History id. */
    val webSockets = WebSocketRegistry()

    /** The most entries kept, and how many older ones have been dropped to stay within it (or the memory budget). */
    val capacity: Int get() = maxSize
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    val droppedCount: Long get() = dropped.get()
    val transactions: StateFlow<List<HttpTransaction>> = _transactions.asStateFlow()

    fun record(tx: HttpTransaction) {
        _transactions.update { current ->
            val next = current + tx
            // subList() alone would return a *view* still backed by next's full array,
            // keeping the "dropped" entries (and their body bytes) reachable — toList()
            // forces a real copy so they're actually eligible for GC.
            val counted = if (next.size > maxSize) next.subList(next.size - maxSize, next.size).toList() else next
            val kept = trimToBudget(counted)
            dropped.addAndGet((next.size - kept.size).toLong())
            kept
        }
    }

    /** Oldest entries go first once the bodies held in memory pass [maxBytes] (the newest entry is always kept). */
    private fun trimToBudget(list: List<HttpTransaction>): List<HttpTransaction> {
        var total = 0L
        for (t in list) total += weight(t)
        if (total <= maxBytes || list.size <= 1) return list
        var drop = 0
        while (drop < list.size - 1 && total > maxBytes) { total -= weight(list[drop]); drop++ }
        return list.subList(drop, list.size).toList()
    }

    private fun weight(t: HttpTransaction): Long = 512L + (t.requestBody?.size ?: 0) + (t.responseBody?.size ?: 0)

    fun clear() {
        _transactions.value = emptyList()
        webSockets.clear()
    }

    /** Removes one entry by id — no-op if it's already gone (already dropped by the cap, e.g.). */
    fun delete(id: String) {
        webSockets.remove(id)
        _transactions.update { current -> current.filterNot { it.id == id } }
    }

    /** Swaps in a whole different list wholesale — how loading a saved project replaces the live capture. */
    fun replaceAll(transactions: List<HttpTransaction>) {
        _transactions.value = if (transactions.size > maxSize) {
            transactions.subList(transactions.size - maxSize, transactions.size).toList()
        } else {
            transactions
        }
    }
}
