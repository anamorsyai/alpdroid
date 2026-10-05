package com.alpdroid.app

/**
 * Decides where each terminal session's processes should run: on every core, or parked on the phone's
 * low-power cores. Pure policy (no Android calls) so it is unit-tested; ResourceManager feeds it the
 * measured CPU of each tab and applies the answer through the pty bridge (`AFF <mask>`).
 *
 * What it optimises: busy sessions nobody is looking at (a build or server in a background tab, or
 * anything while the app is hidden) stop competing for the big cores that the foreground tab, the UI
 * and the user's other apps need, and they run cooler. The tab you are using always gets every core,
 * unless the phone is hot. It only ever touches this app's own processes and never changes priority,
 * so nothing is starved and nothing can hang: the worst case for a parked session is that it runs
 * slower than on a big core.
 */
object LoadBalancer {
    enum class Placement { ALL, EFFICIENCY }

    /** CPU % (of one core) above which a session counts as busy, by situation. */
    const val BUSY_APP_HIDDEN = 40.0
    const val BUSY_BACKGROUND_TAB = 60.0
    const val BUSY_WHEN_HOT = 25.0
    /** Below this a parked session is calm again and gets every core back (bursts then run fast). */
    const val CALM = 15.0
    /** Consecutive samples (ticks) that must agree before the placement actually changes. */
    const val SWITCH_AFTER = 2

    data class Input(val activeTab: Boolean, val appVisible: Boolean, val hot: Boolean, val cpuPercent: Double)

    class State {
        var placement = Placement.ALL
            internal set
        internal var pending: Placement? = null
        internal var pendingCount = 0
    }

    /** What this sample alone argues for. */
    fun desired(input: Input, current: Placement): Placement {
        val busyHot = input.hot && input.cpuPercent > BUSY_WHEN_HOT
        val busyHidden = !input.appVisible && input.cpuPercent > BUSY_APP_HIDDEN
        val busyBackgroundTab = input.appVisible && !input.activeTab && input.cpuPercent > BUSY_BACKGROUND_TAB
        if (busyHot || busyHidden || busyBackgroundTab) return Placement.EFFICIENCY
        if (current == Placement.EFFICIENCY) {
            // Leave the parking spot when the user comes back to it (and the phone is cool), or when it calmed down.
            val userNeedsIt = input.activeTab && input.appVisible && !input.hot
            if (userNeedsIt || input.cpuPercent < CALM) return Placement.ALL
            return Placement.EFFICIENCY
        }
        return Placement.ALL
    }

    /** Feeds one sample; returns the new placement when it should be applied now, null otherwise.
     *  A change needs [SWITCH_AFTER] agreeing samples in a row, except returning to the foreground tab,
     *  which is immediate (the user is waiting). */
    fun step(state: State, input: Input): Placement? {
        val want = desired(input, state.placement)
        if (want == state.placement) { state.pending = null; state.pendingCount = 0; return null }
        val immediate = want == Placement.ALL && input.activeTab && input.appVisible && !input.hot
        if (state.pending == want) state.pendingCount++ else { state.pending = want; state.pendingCount = 1 }
        if (immediate || state.pendingCount >= SWITCH_AFTER) {
            state.placement = want
            state.pending = null
            state.pendingCount = 0
            return want
        }
        return null
    }
}
