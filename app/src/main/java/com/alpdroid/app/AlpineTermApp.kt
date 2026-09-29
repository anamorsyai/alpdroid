package com.alpdroid.app

import android.app.Application
import java.util.concurrent.Executors

class AlpineTermApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OperationNotifications.clearStale(this)
    }

    /** Slow, network-bound work: downloading/extracting Alpine, spawning a session. */
    val backgroundExecutor = Executors.newSingleThreadExecutor()

    /** Package search (AlpineSession.searchPackages) runs its own `apk update` over the network
     *  with a watchdog timeout, but even a bounded hang here must never share a thread with
     *  addTab/startSessionNow/backup/restore on [backgroundExecutor] — a wedged or slow search
     *  would otherwise queue behind (or block) opening a brand-new tab for as long as it takes. */
    val searchExecutor = Executors.newSingleThreadExecutor()

    /** Owned here rather than by MainActivity: `proot` and every shell are child processes of
     *  this same app process, not of any particular Activity instance, and Android can destroy
     *  and recreate the Activity (memory pressure reclaiming a backgrounded one, in particular —
     *  the exact case the foreground keep-alive service exists for) while this process, and
     *  every reader thread already running against these tabs, keeps going untouched. Keeping
     *  the list here instead of as an Activity field is what lets a freshly recreated
     *  MainActivity just re-attach to still-live sessions instead of losing them — real
     *  persistence, not just "remembered how many tabs to recreate" (see SessionPersistence,
     *  which is the fallback for when the process itself, not just the Activity, actually died). */
    val tabs = mutableListOf<TerminalTab>()
    var nextTabId = 1
    var activeTabIndex = -1

    /** How many addTab() calls are currently in flight (a fresh download/setup, a restart, a
     *  reinstall, a restore) — a counter, not a flag, so several genuinely overlapping starts (a
     *  saved-session resume adding several tabs in a row, or a plain "+" tapped while a restart is
     *  still setting up) are all tracked correctly instead of one clearing the others' guard.
     *  Lives here rather than as an Activity field for the exact same reason [tabs] does: a config
     *  change or the system reclaiming a backgrounded Activity would otherwise silently reset a
     *  plain Activity field back to 0 mid-flight, right as onTabExited() needed it to still say
     *  "yes, a replacement really is still coming." See MainActivity.addTab()/startSessionNow()/
     *  showSetupFailure()/onTabExited(). */
    var pendingSessionStarts = 0
}
