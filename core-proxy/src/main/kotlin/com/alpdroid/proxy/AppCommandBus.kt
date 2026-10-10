package com.alpdroid.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Lets the agent's MCP tools drive the app shell itself — which tab is on
 * screen — not just the Browser tab's URL (see [BrowserCommandBus], which
 * this mirrors: a Channel so a command sent while the user is elsewhere
 * queues instead of being dropped, and is consumed exactly once).
 */
class AppCommandBus {
    private val channel = Channel<AppCommand>(Channel.UNLIMITED)
    val commands: Flow<AppCommand> = channel.receiveAsFlow()

    suspend fun navigateTab(route: String) {
        channel.send(AppCommand.NavigateTab(route))
    }
}

sealed interface AppCommand {
    data class NavigateTab(val route: String) : AppCommand
}
