package com.alpdroid.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserCommandBusTest {

    @Test
    fun `a command sent before anything collects is still delivered, not dropped`() = runTest {
        val bus = BrowserCommandBus()

        bus.navigate("https://example.com") // no collector active yet

        val received = bus.commands.first() as BrowserCommand.Navigate
        assertEquals("https://example.com", received.url)
    }

    @Test
    fun `commands are delivered in order`() = runTest {
        val bus = BrowserCommandBus()

        bus.navigate("https://a.test")
        bus.navigate("https://b.test")

        assertEquals("https://a.test", (bus.commands.first() as BrowserCommand.Navigate).url)
        assertEquals("https://b.test", (bus.commands.first() as BrowserCommand.Navigate).url)
    }
}
