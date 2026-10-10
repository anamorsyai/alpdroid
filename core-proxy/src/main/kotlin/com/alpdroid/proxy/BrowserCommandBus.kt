package com.alpdroid.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Lets something outside the Browser tab's own UI (the agent's MCP tools,
 * for instance) drive it — currently just "load this URL." Backed by a
 * Channel, not a SharedFlow: the Browser screen only collects while
 * composed, and a SharedFlow with no replay drops anything emitted while
 * nobody's actively collecting (e.g. the agent navigates while the user is
 * on a different tab). A Channel queues until someone receives it, and
 * each command is consumed exactly once — no risk of it firing again on a
 * later resubscribe/recomposition either.
 */
class BrowserCommandBus {
    private val channel = Channel<BrowserCommand>(Channel.UNLIMITED)
    val commands: Flow<BrowserCommand> = channel.receiveAsFlow()

    suspend fun navigate(url: String) {
        channel.send(BrowserCommand.Navigate(url))
    }
}

sealed interface BrowserCommand {
    data class Navigate(val url: String) : BrowserCommand
}
