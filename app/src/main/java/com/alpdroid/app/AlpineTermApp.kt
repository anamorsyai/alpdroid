package com.alpdroid.app

import android.app.Application
import java.util.concurrent.Executors

class AlpineTermApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Both are fire-and-forget maintenance: a notification-service IPC and a storage
        // enumeration + deletes that used to run synchronously here, stalling the first
        // frame on every cold start. Order-independent vs pluginJobs.start() below.
        backgroundExecutor.execute {
            OperationNotifications.clearStale(this)
            sweepStaleProotScratch()
        }
        pluginJobs.start()
        resourceManager.start()
    }

    /** Adapts to heat, battery saver and memory pressure (see ResourceManager). */
    val resourceManager = ResourceManager(this)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        resourceManager.onTrimMemory(level)
    }

    /** Scheduled / keep-running plugin scripts (see PluginJobs). */
    val pluginJobs = PluginJobs(this)

    /** The opencode web server, the SSH server: listening programs that run without a tab (see BackgroundServices). */
    val services = BackgroundServices(this)

    /**
     * One-off proot runs (plugin buttons, package search) each mint a `proot-scratch-*` dir
     * that nothing ever deleted. Safe to wipe them all here: this runs at process start, and
     * every proot session is a child of this process — none can predate it.
     */
    private fun sweepStaleProotScratch() {
        runCatching {
            (cacheDir.listFiles() ?: emptyArray())
                .filter { it.isDirectory && it.name.startsWith("proot-scratch-") }
                // NoFollow: a symlink planted in a dead scratch dir (same-uid guest code ran
                // here) must never resolve outward — deleteRecursively() would follow it.
                .forEach { runCatching { it.deleteRecursivelyNoFollow() } }
        }
    }

    /** Slow, network-bound work: downloading/extracting Alpine, spawning a session.
     *  Daemon threads: after the deliberate "Exit" path (tabs destroyed, service stopped)
     *  idle leftovers must never keep the process itself alive behind a gone UI. */
    val backgroundExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "alpdroid-bg").apply { isDaemon = true } }

    /** Backup/restore have their own thread: a 5-minute rootfs backup on the single
     *  backgroundExecutor used to wedge "+" (new tab) with no feedback until it finished. */
    val backupRestoreExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "alpdroid-backup").apply { isDaemon = true } }

    /** Package search (AlpineSession.searchPackages) runs its own `apk update` over the network
     *  with a watchdog timeout, but even a bounded hang here must never share a thread with
     *  addTab/startSessionNow/backup/restore on [backgroundExecutor] — a wedged or slow search
     *  would otherwise queue behind (or block) opening a brand-new tab for as long as it takes. */
    val searchExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "alpdroid-search").apply { isDaemon = true } }

    /** Owned here rather than by MainActivity: `proot` and every shell are child processes of
     *  this same app process, not of any particular Activity instance, and Android can destroy
     *  and recreate the Activity (memory pressure reclaiming a backgrounded one, in particular —
     *  the exact case the foreground keep-alive service exists for) while this process, and
     *  every reader thread already running against these tabs, keeps going untouched. Keeping
     *  the list here instead of as an Activity field is what lets a freshly recreated
     *  MainActivity just re-attach to still-live sessions instead of losing them — real
     *  persistence, not just "remembered how many tabs to recreate" (see SessionPersistence,
     *  which is the fallback for when the process itself, not just the Activity, actually died). */
    val tabs = java.util.concurrent.CopyOnWriteArrayList<TerminalTab>()

    /** The local control API for programs in the terminal (off unless the user enables it). Owned
     *  here so it survives Activity recreation; MainActivity attaches/detaches its UI host. */
    val agentBridge = AgentBridge(this)
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
