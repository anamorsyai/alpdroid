package com.alpdroid.proxy

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Broadcasts "the agent just did X" so the Agent Chat screen can show real
 * MCP tool activity live, not just what the user typed — this is what makes
 * "full control" visible rather than a black box. A replaying SharedFlow,
 * not a one-shot Channel like [BrowserCommandBus]/[AppCommandBus]: this is a
 * log multiple observers (or a screen re-opened later) should all be able
 * to see, not a command to consume exactly once.
 */
class AgentEventBus {
    private val flow = MutableSharedFlow<AgentToolEvent>(replay = 20, extraBufferCapacity = 20)
    val events: SharedFlow<AgentToolEvent> = flow.asSharedFlow()

    suspend fun emit(tool: String, summary: String) {
        flow.emit(AgentToolEvent(tool, summary, System.currentTimeMillis()))
    }
}

data class AgentToolEvent(val tool: String, val summary: String, val atMillis: Long)
