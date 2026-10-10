package com.alpdroid.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Lets other screens hand a question to the Agent Chat composer — "Ask AI" on a transaction's
 * detail view, for instance — instead of the user having to switch tabs, remember what they
 * wanted to ask, and retype it. Same reasoning as [BrowserCommandBus]/[RepeaterCommandBus]: a
 * Channel so a command sent while Agent Chat isn't composed queues instead of being dropped, and
 * is consumed exactly once. Only prefills the composer — it never sends on the user's behalf,
 * since there's no live model to actually answer yet (see AgentChatScreen's own doc).
 */
class AgentChatCommandBus {
    private val channel = Channel<AgentChatCommand>(Channel.UNLIMITED)
    val commands: Flow<AgentChatCommand> = channel.receiveAsFlow()

    suspend fun prefill(text: String) {
        channel.send(AgentChatCommand.Prefill(text))
    }
}

sealed interface AgentChatCommand {
    data class Prefill(val text: String) : AgentChatCommand
}
