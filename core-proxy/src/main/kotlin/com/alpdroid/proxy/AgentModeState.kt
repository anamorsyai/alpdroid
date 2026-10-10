package com.alpdroid.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AgentMode { BUILD, ACT }

/**
 * Shared between the Agent Chat screen's Build/Act toggle and the MCP server: in
 * [AgentMode.BUILD] a connected agent can still read (history, transactions) but every
 * mutating tool (resend_request, navigate_browser, navigate_app) refuses to run, so nothing
 * actually happens in the app until the user deliberately flips to [AgentMode.ACT]. Defaults
 * to BUILD — an agent should never get to act just because it connected.
 */
class AgentModeState {
    private val state = MutableStateFlow(AgentMode.BUILD)
    val mode: StateFlow<AgentMode> = state

    fun set(mode: AgentMode) {
        state.value = mode
    }
}
