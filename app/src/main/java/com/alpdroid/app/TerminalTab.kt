package com.alpdroid.app

import com.alpdroid.app.terminal.TerminalEmulator

/** One tab's independent shell + screen buffer — [MainActivity] holds a list of these and
 *  reattaches the single [com.alpdroid.app.terminal.TerminalView] to whichever is active. */
class TerminalTab(
    val id: Int,
    val session: PtySession,
    val emulator: TerminalEmulator,
    val backendLabel: String,
    /** User-assigned name (long-press a tab → Rename) — null falls back to the plain "N" the tab
     *  bar has always shown. Distinct sessions become hard to tell apart once several are
     *  running something different in the background. */
    var label: String? = null,
    /** The last SSH quick-connect command sent to this tab, if any — remembered so a dropped
     *  connection (the network flaking mid-session, common enough that this app spent a whole
     *  debugging session on exactly that) can be reconnected in one tap from the tab menu
     *  instead of hunting back through Settings for the same profile. */
    var lastSshCommand: String? = null,
) {
    /** Set by whichever MainActivity instance currently owns the UI, and re-set every time one
     *  does (including a freshly recreated Activity re-attaching to this same tab) — the reader
     *  thread that calls this outlives any single Activity instance, so it can't just close over
     *  a TerminalView directly, or output arriving while the Activity was destroyed and being
     *  recreated would poke a defunct, no-longer-displayed view instead of the new one. */
    @Volatile var onOutput: (() -> Unit)? = null

    /** Same reasoning and same re-binding as [onOutput] — the reader thread that calls this on
     *  EOF outlives any one MainActivity instance, so it must never close over `this@MainActivity`
     *  directly (see startReaderThread()/rebindTabOutputs()), or a shell exiting after the system
     *  recreated the Activity would update the dead instance's tab list and views instead of the
     *  live one's. */
    @Volatile var onExit: (() -> Unit)? = null

    /** Started by a one-tap "server" button (SSH, opencode web) rather than as an interactive shell:
     *  Ctrl+C is how the user stops it, and pressing it twice quickly force-stops a server that
     *  ignores or cannot handle the first one. */
    @Volatile var isServer = false

    /** Uptime of the last lone Ctrl+C sent to a server tab (0 = none), for the double-press check. */
    @Volatile var lastCtrlCMs = 0L
}
