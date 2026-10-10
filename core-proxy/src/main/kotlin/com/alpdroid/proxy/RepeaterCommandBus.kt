package com.alpdroid.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Lets History and Sitemap send a captured transaction straight into the Repeater tab's editor
 * — the classic "send to repeater" hand-off — instead of Repeater being an island the user has
 * to separately navigate to and re-find the same request in its own list. Same reasoning as
 * [BrowserCommandBus]: a Channel, not a SharedFlow, so a command sent while Repeater isn't
 * composed (the user is elsewhere, or the navigation to open it hasn't landed yet) queues
 * instead of being silently dropped, and is consumed exactly once.
 */
class RepeaterCommandBus {
    private val channel = Channel<RepeaterCommand>(Channel.UNLIMITED)
    val commands: Flow<RepeaterCommand> = channel.receiveAsFlow()

    suspend fun open(transaction: HttpTransaction) {
        channel.send(RepeaterCommand.Open(transaction))
    }
}

sealed interface RepeaterCommand {
    data class Open(val transaction: HttpTransaction) : RepeaterCommand
}
