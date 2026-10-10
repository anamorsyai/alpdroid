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
class TransactionStore(private val maxSize: Int = 500) {
    private val _transactions = MutableStateFlow<List<HttpTransaction>>(emptyList())
    val transactions: StateFlow<List<HttpTransaction>> = _transactions.asStateFlow()

    fun record(tx: HttpTransaction) {
        _transactions.update { current ->
            val next = current + tx
            // subList() alone would return a *view* still backed by next's full array,
            // keeping the "dropped" entries (and their body bytes) reachable — toList()
            // forces a real copy so they're actually eligible for GC.
            if (next.size > maxSize) next.subList(next.size - maxSize, next.size).toList() else next
        }
    }

    fun clear() {
        _transactions.value = emptyList()
    }

    /** Removes one entry by id — no-op if it's already gone (already dropped by the cap, e.g.). */
    fun delete(id: String) {
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
