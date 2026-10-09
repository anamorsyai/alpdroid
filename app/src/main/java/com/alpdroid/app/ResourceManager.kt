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
    data class TabUsage(val tabId: Int, val title: String, val cpuPercent: Double, val rssBytes: Long, val parked: Boolean = false, val top: String = "")

    @Volatile var mode: ResourcePolicy.Mode = ResourcePolicy.Mode.NORMAL
        private set
    @Volatile private var thermalStatus = 0
    @Volatile private var usage: List<TabUsage> = emptyList()
    @Volatile private var appRssBytes = 0L
    /** How many processes the sessions had at the last sample (bridges + everything under them). Android kills an
     *  app's child processes above 32 ("phantom process" limit), so it is shown in the usage view. */
    @Volatile var processCount = 0
        private set
    /** Highest [processCount] seen since the sessions started — a kill is usually reported after the burst of
     *  processes that caused it is already gone, so the last sample alone would hide it. */
    /** The busiest processes (name and CPU%) of the busiest session at the last sample, for the kill message. */
    @Volatile var busiest = ""
        private set
    @Volatile var peakProcessCount = 0
        private set

    /** Free memory right now in MB, or -1 when unreadable. */
    fun memAvailableMb(): Long = readText("/proc/meminfo")?.let { ResourcePolicy.parseMemAvailableMb(it) } ?: -1L
    @Volatile private var renderLine = ""
    private var lastRender: RenderStats.Snapshot? = null
    private var lastRenderAtMs = 0L

    /** Cached: [frameIntervalMs] is read on every reader chunk, and building a SettingsStore + reading
     *  SharedPreferences each time was pure overhead. Refreshed by every tick and by [onSettingsChanged]. */
    @Volatile private var enabledCache = true
    private val enabled: Boolean get() = enabledCache

    private fun refreshEnabled() { enabledCache = runCatching { SettingsStore(app).resourceManagerEnabled }.getOrDefault(true) }

    /** Called when the Smart resource manager switch changes. */
    fun onSettingsChanged() = refreshEnabled()

    /** What TerminalView should wait between output-driven repaints. */
    val frameIntervalMs: Long get() = if (enabled) mode.frameIntervalMs else UNCAPPED_FRAME_MS

    private class Sample(val bridgeJiffies: Long, val guestJiffies: Long, val atMs: Long)

    private val samples = HashMap<Int, Sample>()
    /** Background services: id -> (CPU time, when) at the previous tick. */
    private val serviceSamples = HashMap<String, Pair<Long, Long>>()
    /** One line per running background service (name, CPU, memory, busiest process) for the usage view and Settings. */
    @Volatile var serviceUsage: List<Pair<String, String>> = emptyList()
        private set
    /** Per-process CPU time at the previous tick, to tell which process inside a session is the busy one. */
    private var prevPidJiffies = HashMap<Int, Long>()
    private val balancerStates = HashMap<Int, LoadBalancer.State>()
    private var wasBalancing = false
    /** Low-power cores of this phone (hex mask), looked up once; null when they can't be identified. */
    private val efficiencyMask: String? by lazy { CpuTopology.readEfficiencyMask() }

    /** Dynamic balancing needs an identifiable set of low-power cores, the user's switch on, and no
     *  static "all tabs on efficiency cores" pin (which would fight it). */
    private fun balancingActive(): Boolean = runCatching {
        val s = SettingsStore(app)
        enabled && s.smartBalancing && !s.efficiencyCores && efficiencyMask != null
    }.getOrDefault(false)

    /** One balancing step for [tab]; returns whether it is currently parked on the efficiency cores. */
    private fun balance(tab: TerminalTab, isActive: Boolean, cpu: Double, active: Boolean): Boolean {
        val state = balancerStates.getOrPut(tab.id) { LoadBalancer.State() }
        if (!active) {
            // Switched off (or not applicable): give any parked session its cores back, once.
            if (state.placement == LoadBalancer.Placement.EFFICIENCY) {
                tab.session.setCpuAffinity(null)
                balancerStates.remove(tab.id)
            }
            return false
        }
        val input = LoadBalancer.Input(isActive, appVisible, mode == ResourcePolicy.Mode.COOL, cpu)
        val before = state.placement
        val change = LoadBalancer.step(state, input)
        if (change != null) {
            val delivered = when (change) {
                LoadBalancer.Placement.EFFICIENCY -> tab.session.setCpuAffinity(efficiencyMask)
                LoadBalancer.Placement.ALL -> tab.session.setCpuAffinity(null)
            }
            // The control channel may not be open yet (or the write failed): the balancer already committed
            // the change, so undo it — the next tick then tries again instead of assuming it took effect.
            if (!delivered) state.placement = before
        }
        return state.placement == LoadBalancer.Placement.EFFICIENCY
    }
    private val detectors = HashMap<Int, ResourcePolicy.RunawayDetector>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "alpdroid-resources").apply { isDaemon = true } }

    /** False while the app is in the background: ticks then skip the mode/render bookkeeping that only
     *  matters for what is on screen (the runaway-session check still runs). */
    @Volatile var appVisible = true

    fun start() {
        refreshEnabled()
        scheduleNextTick(5)
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

    /**
     * Ticks every 15 s while the app is on screen or something is busy, and every 45 s when the app is in the
     * background and everything is quiet — each tick lists /proc, which is a permanent wake-up for nothing then. A
     * busy session (CPU at or above [BUSY_CPU]) brings the 15 s rhythm straight back, so the frozen-session check and the
     * load balancer stay responsive exactly when they matter.
     */
    private fun scheduleNextTick(delaySeconds: Long) {
        scheduler.schedule({
            runCatching { tick() }
            scheduleNextTick(if (appVisible || lastMaxCpu >= BUSY_CPU) TICK_SECONDS else QUIET_TICK_SECONDS)
        }, delaySeconds, TimeUnit.SECONDS)
    }

    /** Highest CPU% of any session or service at the last tick. */
    @Volatile private var lastMaxCpu = 0.0

    @Synchronized
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

    private fun updateRenderLine() {
        val now = System.currentTimeMillis()
        val cur = RenderStats.snapshot()
        val prev = lastRender
        if (prev != null && now - lastRenderAtMs >= 1000) {
            val dt = (now - lastRenderAtMs) / 1000.0
            val drawn = cur.drawn - prev.drawn
            val skipped = cur.skipped - prev.skipped
            renderLine = if (drawn + skipped == 0L) "Terminal: idle"
            else "Terminal: %.0f frames/s, %.1f ms each, %d%% of repaints skipped (nothing changed)".format(
                drawn / dt,
                if (drawn > 0) (cur.drawNanos - prev.drawNanos) / 1e6 / drawn else 0.0,
                if (drawn + skipped > 0) (skipped * 100 / (drawn + skipped)).toInt() else 0,
            )
            lastRender = cur
            lastRenderAtMs = now
        } else if (prev == null) {
            lastRender = cur
            lastRenderAtMs = now
        }
    }

    private fun tick() {
        refreshEnabled()
        if (appVisible) {
            recomputeMode()
            updateRenderLine()
        }
        appRssBytes = readText("/proc/self/statm")?.let { ResourcePolicy.parseStatmRssBytes(it) } ?: 0L
        val tabs = app.tabs.toList()
        val services = app.services.list()
        if (tabs.isEmpty() && services.isEmpty()) { usage = emptyList(); serviceUsage = emptyList(); processCount = 0; peakProcessCount = 0; busiest = ""; samples.clear(); detectors.clear(); serviceSamples.clear(); prevPidJiffies = HashMap(); lastMaxCpu = 0.0; return }

        // One pass over /proc: ppid + cpu of every process we are allowed to see (our own uid).
        val parentOf = HashMap<Int, Int>()
        val jiffiesOf = HashMap<Int, Long>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val stat = readText("/proc/$pid/stat")?.let { ResourcePolicy.parseStat(it) } ?: return@forEach
            parentOf[pid] = stat.ppid
            jiffiesOf[pid] = stat.cpuJiffies
        }

        val balancing = balancingActive()
        if (balancing && !wasBalancing) {
            // Just switched on: tabs opened while the fixed efficiency-cores pin was on are still
            // restricted — hand them every core back; the balancer takes over from here.
            tabs.forEach { it.session.setCpuAffinity(null) }
        }
        wasBalancing = balancing
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
            val parked = balance(tab, index == app.activeTabIndex, cpu, balancing)
            val top = if (prev == null) "" else (tree + bridge)
                .mapNotNull { pid ->
                    val before = prevPidJiffies[pid] ?: return@mapNotNull null
                    val pct = ResourcePolicy.cpuPercent((jiffiesOf[pid] ?: before) - before, now - prev.atMs)
                    if (pct < 10.0) null else pid to pct
                }
                .sortedByDescending { it.second }.take(3)
                .joinToString(", ") { (pid, pct) -> (readText("/proc/$pid/comm")?.trim().orEmpty().ifEmpty { "pid $pid" }) + " " + pct.toInt() + "%" }
            result += TabUsage(tab.id, tab.label ?: "Session ${index + 1}", cpu, rss, parked, top)
        }
        usage = result
        busiest = result.maxByOrNull { it.cpuPercent }?.top.orEmpty()
        processCount = tabs.sumOf { tab ->
            val bridge = tab.session.pid
            if (bridge <= 0 || !parentOf.containsKey(bridge)) 0 else 1 + ResourcePolicy.descendants(bridge, parentOf).size
        }
        // Background services (opencode web, sshd …) count towards Android's child-process limit and use CPU like a tab.
        val svcLines = ArrayList<Pair<String, String>>()
        var svcBusyCpu = 0.0
        var svcBusyTop = ""
        val liveServices = HashSet<String>()
        for (svc in services) {
            val pid = svc.session.pid
            if (pid <= 0 || !jiffiesOf.containsKey(pid)) continue
            liveServices += svc.id
            val tree = ResourcePolicy.descendants(pid, parentOf) + pid
            processCount += tree.size
            val total = tree.sumOf { jiffiesOf[it] ?: 0L }
            val rss = tree.sumOf { p -> readText("/proc/$p/statm")?.let { ResourcePolicy.parseStatmRssBytes(it) } ?: 0L }
            val before = serviceSamples[svc.id]
            serviceSamples[svc.id] = total to now
            val cpu = if (before == null) 0.0 else ResourcePolicy.cpuPercent(total - before.first, now - before.second)
            val top = if (before == null) "" else tree
                .mapNotNull { p ->
                    val was = prevPidJiffies[p] ?: return@mapNotNull null
                    val pct = ResourcePolicy.cpuPercent((jiffiesOf[p] ?: was) - was, now - before.second)
                    if (pct < 10.0) null else p to pct
                }
                .sortedByDescending { it.second }.take(3)
                .joinToString(", ") { (p, pct) -> (readText("/proc/$p/comm")?.trim().orEmpty().ifEmpty { "pid $p" }) + " " + pct.toInt() + "%" }
            if (cpu > svcBusyCpu && top.isNotEmpty()) { svcBusyCpu = cpu; svcBusyTop = svc.label + ": " + top }
            svcLines += svc.id to ("CPU " + cpu.toInt() + "% · RAM " + rss / (1024 * 1024) + " MB" + if (top.isNotEmpty()) " · busiest: $top" else "")
        }
        serviceUsage = svcLines
        lastMaxCpu = maxOf(result.maxOfOrNull { it.cpuPercent } ?: 0.0, svcBusyCpu)
        // The kill message names the busiest process wherever it runs — a tab or a background service.
        if (svcBusyCpu > (result.maxOfOrNull { it.cpuPercent } ?: 0.0)) busiest = svcBusyTop
        serviceSamples.keys.retainAll(liveServices)
        prevPidJiffies = HashMap(jiffiesOf)
        if (processCount > peakProcessCount) peakProcessCount = processCount
        samples.keys.retainAll(liveIds)
        detectors.keys.retainAll(liveIds)
        balancerStates.keys.retainAll(liveIds)
    }

    /** Called from [AlpineTermApp.onTrimMemory]: Android is short on memory — release what is cheapest
     *  to lose first (old scrollback of tabs nobody is looking at). */
    fun onTrimMemory(level: Int) {
        if (!enabled) return
        val active = app.tabs.getOrNull(app.activeTabIndex)
        // Levels: 10/15 = running low/critical, 20 = UI hidden (every Home press — NOT memory pressure,
        // must not cost the user their history), 40/60/80 = our process is cached and may be killed.
        val (idleKeep, activeKeep) = when {
            level >= TRIM_COMPLETE_ISH -> 100 to 500
            level >= TRIM_BACKGROUND -> 300 to Int.MAX_VALUE
            level >= TRIM_UI_HIDDEN -> return
            level >= TRIM_RUNNING_CRITICAL -> 100 to 500
            level >= TRIM_RUNNING_LOW -> 300 to Int.MAX_VALUE
            else -> return
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
        if (renderLine.isNotEmpty()) sb.append("\n").append(renderLine)
        if (processCount > 0) {
            sb.append("\nProcesses: ").append(processCount)
            if (processCount >= 26) sb.append(" — close to Android's limit of 32 for an app's child processes; above it Android kills them (see \"Copy adb fix\" above)")
        }
        for (u in usage) {
            sb.append("\n").append(u.title).append(": CPU ").append(u.cpuPercent.toInt()).append("% · RAM ").append(u.rssBytes / (1024 * 1024)).append(" MB")
            if (u.parked) sb.append(" · parked on efficiency cores")
            if (u.top.isNotEmpty()) sb.append("\n   busiest: ").append(u.top)
        }
        for ((id, line) in serviceUsage) {
            val label = app.services.get(id)?.label ?: id
            sb.append("\n").append(label).append(" (background): ").append(line)
        }
        if (usage.isEmpty() && serviceUsage.isEmpty()) sb.append("\nNo sessions running.")
        return sb.toString()
    }

    /** Takes a fresh reading right now (Settings "Refresh"), off the main thread. */
    fun refreshNow() { scheduler.execute { runCatching { tick() } } }

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private companion object {
        const val TAG = "AlpDroid/Resources"
        const val TICK_SECONDS = 15L
        const val QUIET_TICK_SECONDS = 45L
        const val BUSY_CPU = 25.0
        const val UNCAPPED_FRAME_MS = 8L
        // android.content.ComponentCallbacks2.TRIM_MEMORY_*
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_RUNNING_CRITICAL = 15
        const val TRIM_UI_HIDDEN = 20
        const val TRIM_BACKGROUND = 40
        const val TRIM_COMPLETE_ISH = 60
    }
}
