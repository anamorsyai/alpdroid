package com.alpdroid.proxy

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionStoreTest {

    @Test
    fun `keeps transactions under the cap untouched`() {
        val store = TransactionStore(maxSize = 5)
        repeat(3) { store.record(tx("id-$it")) }

        assertEquals(listOf("id-0", "id-1", "id-2"), store.transactions.value.map { it.id })
    }

    @Test
    fun `drops the oldest entries once past the cap`() {
        val store = TransactionStore(maxSize = 3)
        repeat(5) { store.record(tx("id-$it")) }

        assertEquals(3, store.transactions.value.size)
        assertEquals(listOf("id-2", "id-3", "id-4"), store.transactions.value.map { it.id })
    }

    @Test
    fun `clear empties the store`() {
        val store = TransactionStore()
        store.record(tx("id-0"))
        store.clear()

        assertEquals(emptyList(), store.transactions.value)
    }

    @Test
    fun `replaceAll swaps in a whole different list, as loading a saved project does`() {
        val store = TransactionStore()
        store.record(tx("live-0"))

        store.replaceAll(listOf(tx("saved-0"), tx("saved-1")))

        assertEquals(listOf("saved-0", "saved-1"), store.transactions.value.map { it.id })
    }

    @Test
    fun `replaceAll still respects the cap`() {
        val store = TransactionStore(maxSize = 2)

        store.replaceAll(listOf(tx("a"), tx("b"), tx("c")))

        assertEquals(listOf("b", "c"), store.transactions.value.map { it.id })
    }

    @Test
    fun `delete removes just the matching entry`() {
        val store = TransactionStore()
        store.record(tx("id-0"))
        store.record(tx("id-1"))
        store.record(tx("id-2"))

        store.delete("id-1")

        assertEquals(listOf("id-0", "id-2"), store.transactions.value.map { it.id })
    }

    @Test
    fun `delete is a no-op for an id that isn't present`() {
        val store = TransactionStore()
        store.record(tx("id-0"))

        store.delete("missing")

        assertEquals(listOf("id-0"), store.transactions.value.map { it.id })
    }

    private fun tx(id: String) = HttpTransaction(
        id = id,
        source = TrafficSource.BROWSER,
        timestamp = Instant.now(),
        method = "GET",
        scheme = "https",
        host = "example.com",
        port = 443,
        path = "/",
    )
}
