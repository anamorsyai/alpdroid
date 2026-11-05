package com.alpdroid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The browser-awareness toggle: what agents are told about AlpBrowser, and what is left when it is off. */
class AgentContextBrowserToggleTest {

    private val v = "1.7.98"

    @Test
    fun `note mentions the browser when awareness is on`() {
        val text = AgentContext.text(v, agentAccessOn = true, browserAware = true)
        assertTrue(text.contains("AlpBrowser"))
        assertTrue(text.contains("browserctl"))
        assertTrue(text.contains("The built-in browser"))
    }

    @Test
    fun `note never mentions the browser when awareness is off`() {
        val text = AgentContext.text(v, agentAccessOn = true, browserAware = false)
        assertFalse(text.contains("AlpBrowser"))
        assertFalse(text.contains("browserctl"))
        assertFalse(text.contains("The built-in browser"))
        // The rest of the note is untouched.
        assertTrue(text.contains("Alpine Linux"))
        assertTrue(text.contains("## Controlling the app"))
        assertTrue(text.contains("alpctl"))
    }

    @Test
    fun `off note also holds when agent access is off`() {
        val text = AgentContext.text(v, agentAccessOn = false, browserAware = false)
        assertFalse(text.contains("AlpBrowser"))
        assertFalse(text.contains("browserctl"))
        assertTrue(text.contains("Agent access is OFF"))
    }

    @Test
    fun `awareness on is the default for existing callers`() {
        // Old call sites (two positional args) must keep producing today's note, browser section included.
        val text = AgentContext.text(v, agentAccessOn = true)
        assertTrue(text.contains("AlpBrowser"))
    }
}
