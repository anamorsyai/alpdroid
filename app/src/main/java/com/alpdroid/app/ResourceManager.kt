package com.alpdroid.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps AlpDroid cheap to run: it watches the phone (temperature, battery saver, battery level), the
 * app's own memory, and every session's processes, and adjusts how much work the app does.
 *
 *  - Repaint rate: terminals repaint at most ~30x/s under sustained output (full-screen programs
 *    used to repaint on every vsync, most of the heat), ~20x/s in Eco, ~10x/s when the phone is hot.
 *  - Memory: when Android signals memory pressure, scrollback of background tabs is trimmed first.
 *  - Runaway protection: a session whose native bridge burns a core while everything it serves is
 *    idle is frozen, not busy — it is closed (with a notification) instead of draining the battery.
 *  - Visibility: nothing is painted while the terminal isn't on screen (see TerminalView).
 *
 * The decisions live in [ResourcePolicy] (unit-tested); this class only gathers the numbers.
 * Everything here is cheap: one pass over /proc every 15 seconds.
 */
class ResourceManager(private val app: AlpineTermApp) {
    data class TabUsage(val tabId: Int, val title: String, val cpuPercent: Double, val rssBytes: Long)

    @Volatile var mode: ResourcePolicy.Mode = ResourcePolicy.Mode.NORMAL
        private set
    @Volatile private var thermalStatus = 0
    @Volatile private var usage: List<TabUsage> = emptyList()
    @Volatile private var appRssBytes = 0L

    private val enabled: Boolean get() = runCatching { SettingsStore(app).resourceManagerEnabled }.getOrDefault(true)

    /** What TerminalView should wait between output-driven repaints. */
    val frameIntervalMs: Long get() = if (enabled) mode.frameIntervalMs else UNCAPPED_FRAME_MS

    private class Sample(val bridgeJiffies: Long, val guestJiffies: Long, val atMs: Long)

    private val samples = HashMap<Int, Sample>()
    private val detectors = HashMap<Int, ResourcePolicy.RunawayDetector>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "alpdroid-resources").apply { isDaemon = true } }

    fun start() {
        scheduler.scheduleWithFixedDelay({ runCatching { tick() } }, 5, TICK_SECONDS, TimeUnit.SECONDS)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.addThermalStatusListener({ it.run() }) { status ->
                    thermalStatus = status
                    recomputeMode()
                }
            }
        }
    }

    private fun recomputeMode() {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val next = ResourcePolicy.mode(thermalStatus, pm.isPowerSaveMode, percent, charging)
        if (next != mode) {
            Log.i(TAG, "mode ${mode.label} -> ${next.label} (thermal=$thermalStatus saver=${pm.isPowerSaveMode} battery=$percent charging=$charging)")
            mode = next
        }
    }

    private fun tick() {
        recomputeMode()
        appRssBytes = readText("/proc/self/statm")?.let { ResourcePolicy.parseStatmRssBytes(it) } ?: 0L
        val tabs = app.tabs.toList()
        if (tabs.isEmpty()) { usage = emptyList(); samples.clear(); detectors.clear(); return }

        // One pass over /proc: ppid + cpu of every process we are allowed to see (our own uid).
        val parentOf = HashMap<Int, Int>()
        val jiffiesOf = HashMap<Int, Long>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val stat = readText("/proc/$pid/stat")?.let { ResourcePolicy.parseStat(it) } ?: return@forEach
            parentOf[pid] = stat.ppid
            jiffiesOf[pid] = stat.cpuJiffies
        }

        val now = System.currentTimeMillis()
        val result = ArrayList<TabUsage>()
        val liveIds = HashSet<Int>()
        tabs.forEachIndexed { index, tab ->
            val bridge = tab.session.pid
            if (bridge <= 0 || !jiffiesOf.containsKey(bridge)) return@forEachIndexed
            liveIds += tab.id
            val tree = ResourcePolicy.descendants(bridge, parentOf)
            val bridgeJ = jiffiesOf[bridge] ?: 0L
            val guestJ = tree.sumOf { jiffiesOf[it] ?: 0L }
            val rss = (tree + bridge).sumOf { pid -> readText("/proc/$pid/statm")?.let { ResourcePolicy.parseStatmRssBytes(it) } ?: 0L }
            val prev = samples[tab.id]
            samples[tab.id] = Sample(bridgeJ, guestJ, now)
            var cpu = 0.0
            if (prev != null) {
                val dt = now - prev.atMs
                val bridgeCpu = ResourcePolicy.cpuPercent(bridgeJ - prev.bridgeJiffies, dt)
                val guestCpu = ResourcePolicy.cpuPercent(guestJ - prev.guestJiffies, dt)
                cpu = bridgeCpu + guestCpu
                if (enabled && detectors.getOrPut(tab.id) { ResourcePolicy.RunawayDetector() }.update(bridgeCpu, guestCpu)) {
                    Log.w(TAG, "tab ${tab.id}: bridge pid $bridge used ${bridgeCpu.toInt()}% CPU with an idle guest — closing the frozen session")
                    detectors.remove(tab.id)
                    OperationNotifications.alert(
                        app, OperationNotifications.newId(), "A frozen session was closed",
                        "Session ${index + 1} was using a CPU core while doing nothing, so AlpDroid closed it to save battery.",
                    )
                    tab.session.destroy()
                }
            }
            result += TabUsage(tab.id, tab.label ?: "Session ${index + 1}", cpu, rss)
        }
        usage = result
        samples.keys.retainAll(liveIds)
        detectors.keys.retainAll(liveIds)
    }

    /** Called from [AlpineTermApp.onTrimMemory]: Android is short on memory — release what is cheapest
     *  to lose first (old scrollback of tabs nobody is looking at). */
    fun onTrimMemory(level: Int) {
        if (!enabled || level < TRIM_RUNNING_LOW) return
        val active = app.tabs.getOrNull(app.activeTabIndex)
        val (idleKeep, activeKeep) = when {
            level >= TRIM_BACKGROUND -> 100 to 500
            level >= TRIM_RUNNING_CRITICAL -> 100 to 500
            else -> 300 to Int.MAX_VALUE
        }
        var freed = 0
        for (tab in app.tabs) freed += tab.emulator.trimScrollback(if (tab === active) activeKeep else idleKeep)
        if (freed > 0) Log.i(TAG, "memory level $level: trimmed $freed scrollback rows")
    }

    /** Human-readable status for the Settings screen. */
    fun statusText(): String {
        val sb = StringBuilder()
        sb.append("Mode: ").append(mode.label).append(" (terminal repaints up to ").append(1000 / frameIntervalMs).append("/s)\n")
        sb.append("App memory: ").append(appRssBytes / (1024 * 1024)).append(" MB")
        for (u in usage) {
            sb.append("\n").append(u.title).append(": CPU ").append(u.cpuPercent.toInt()).append("% · RAM ").append(u.rssBytes / (1024 * 1024)).append(" MB")
        }
        if (usage.isEmpty()) sb.append("\nNo sessions running.")
        return sb.toString()
    }

    /** Takes a fresh reading right now (Settings "Refresh"), off the main thread. */
    fun refreshNow() { scheduler.execute { runCatching { tick() } } }

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private companion object {
        const val TAG = "AlpDroid/Resources"
        const val TICK_SECONDS = 15L
        const val UNCAPPED_FRAME_MS = 8L
        // android.content.ComponentCallbacks2.TRIM_MEMORY_*
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_RUNNING_CRITICAL = 15
        const val TRIM_BACKGROUND = 40
    }
}
