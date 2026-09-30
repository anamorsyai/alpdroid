package com.alpdroid.app

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs plugin scripts without a screen open: scheduled ones every N minutes and "keep running"
 * ones as a supervised background process (restarted if they exit). A job runs only while its
 * switch is on AND the plugin's scripts have been approved by the user. This lives in the app
 * process — the foreground keep-alive service is what stops Android from reaping it — so nothing
 * runs after a reboot until the app has been opened once. Output goes to
 * <plugin>/logs/<job>.log (capped), never to the UI.
 */
class PluginJobs(private val app: AlpineTermApp) {
    data class Job(val plugin: Plugins.Plugin, val id: String, val label: String, val script: String, val everyMinutes: Int?)

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "plugin-job").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "plugin-scheduler").apply { isDaemon = true } }
    private val running = ConcurrentHashMap<String, PtySession>()
    private val watchdogs = ConcurrentHashMap<String, Thread>()
    private val lastStart = ConcurrentHashMap<String, Long>()
    private val lastEnd = ConcurrentHashMap<String, Long>()
    @Volatile var enabledCount = 0
        private set

    /** Set by the keep-alive service's Exit action until the app is opened again, so "Exit" really stops everything. */
    @Volatile var paused = false

    fun start() {
        scheduler.scheduleWithFixedDelay({ runCatching { tick() } }, 15, 30, TimeUnit.SECONDS)
    }

    fun key(p: Plugins.Plugin, jobId: String) = "${p.id}/$jobId"

    fun jobsOf(p: Plugins.Plugin): List<Job> =
        p.schedules.map { Job(p, it.id, it.label, it.script, it.everyMinutes) } +
            p.buttons.filter { it.background }.map { Job(p, it.id, it.label, it.script, null) }

    /** Whether the process needs keeping alive for jobs. */
    fun hasActive(): Boolean = !paused && (enabledCount > 0 || running.isNotEmpty())

    fun refreshCount() {
        enabledCount = runCatching { Plugins.list(app).sumOf { p -> jobsOf(p).count { Plugins.isEnabled(p, it.id) } } }.getOrDefault(0)
    }

    private fun tick() {
        if (paused || !AlpineRootfs.isReady(app)) return
        val now = System.currentTimeMillis()
        var count = 0
        // Approval + switch states read once per plugin: the old loop re-hashed the plugin
        // (walk + SHA-256) and re-read state.json per job — O(jobs) redundant I/O per tick.
        for (p in Plugins.list(app)) {
            val approved = Plugins.isApproved(app, p)
            for (j in jobsOf(p)) {
                if (!Plugins.isEnabled(p, j.id)) continue
                count++
                val k = key(p, j.id)
                if (running.containsKey(k)) {
                    // Approval revoked (or scripts changed) while the job was already running —
                    // kill it now rather than letting revoked code finish on its own terms.
                    if (!approved) running.remove(k)?.let { runCatching { it.destroy() } }
                    continue
                }
                if (!approved) continue
                val due = if (j.everyMinutes == null) now - (lastEnd[k] ?: 0L) >= 30_000L
                else (lastStart[k] ?: 0L) + j.everyMinutes * 60_000L <= now
                if (due) launch(j)
            }
        }
        enabledCount = count
    }

    fun launch(j: Job) {
        val k = key(j.plugin, j.id)
        if (running.containsKey(k)) return
        lastStart[k] = System.currentTimeMillis() // set up front so a failing start can't spin
        pool.execute { runCatching { execute(j, k) } }
    }

    private fun execute(j: Job, k: String) {
        val session = Plugins.runScript(app, j.plugin, j.script, j.id, Plugins.loadState(j.plugin)) ?: return
        if (running.putIfAbsent(k, session) != null) { session.destroy(); return }
        val log = File(j.plugin.dir, "logs/${j.id}.log").also { it.parentFile?.mkdirs() }
        if (log.length() > MAX_LOG) runCatching { log.writeText(log.readText().takeLast(MAX_LOG / 2)) }
        val stamp = { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) }
        log.appendText("=== started ${stamp()} ===\n")
        val watchdog = if (j.everyMinutes != null) Thread({
            try { Thread.sleep(5 * 60_000L); runCatching { session.destroy() } } catch (_: InterruptedException) {}
        }, "plugin-job-watchdog").apply { isDaemon = true; start() } else null
        watchdog?.let { watchdogs[k] = it }
        var written = 0L
        runCatching {
            val buf = ByteArray(4096)
            while (true) {
                val n = session.stdout.read(buf)
                if (n <= 0) break
                val text = ANSI.replace(String(buf, 0, n, Charsets.UTF_8).replace("\r", ""), "")
                written += text.length
                if (written <= MAX_LOG) log.appendText(text)
            }
        }
        watchdog?.interrupt()
        watchdogs.remove(k)
        runCatching { session.destroy() }
        running.remove(k)
        lastEnd[k] = System.currentTimeMillis()
        runCatching { log.appendText("\n=== ended ${stamp()} ===\n") }
    }

    fun stop(j: Job) {
        running.remove(key(j.plugin, j.id))?.let { runCatching { it.destroy() } }
        // Otherwise the 5-minute watchdog sleeps on after every manual stop — one zombie
        // sleeper per stop/start cycle that later fires a no-op destroy().
        watchdogs.remove(key(j.plugin, j.id))?.interrupt()
    }

    fun stopAll() {
        running.keys.toList().forEach { k ->
            running.remove(k)?.let { runCatching { it.destroy() } }
            watchdogs.remove(k)?.interrupt()
        }
    }

    fun isRunning(j: Job) = running.containsKey(key(j.plugin, j.id))

    fun status(j: Job): String {
        val k = key(j.plugin, j.id)
        val fmt = { t: Long -> java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) }
        return when {
            running.containsKey(k) -> "running since ${fmt(lastStart[k] ?: 0L)}"
            !Plugins.isEnabled(j.plugin, j.id) -> "off"
            !Plugins.isApproved(app, j.plugin) -> "waiting for your review of the scripts"
            lastEnd.containsKey(k) -> "last finished ${fmt(lastEnd[k]!!)}" + (j.everyMinutes?.let { " • next in ~${((lastStart[k] ?: 0L) + it * 60_000L - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1} min" } ?: " • restarting")
            else -> "starting soon"
        }
    }

    fun tailLog(j: Job, chars: Int = 6000): String =
        runCatching { File(j.plugin.dir, "logs/${j.id}.log").readText().takeLast(chars) }.getOrDefault("(no log yet)").ifBlank { "(empty)" }

    private companion object {
        const val MAX_LOG = 200_000
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
    }
}
