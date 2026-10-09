package com.alpdroid.app

import android.content.Intent
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Long-running programs that only listen or loop — the opencode web server, the SSH server — run here, as
 * background sessions with no tab: nothing to look at, nothing that takes a place in the tab bar. Each one is a plain
 * non-interactive proot session (see [AlpineSession.startScript]) whose output is read by one small thread into a
 * bounded text log (so the app can find the printed password and show a log), and each has a Stop button in
 * Settings → Network & SSH.
 *
 * Like [PluginJobs], this lives in the app process; the foreground keep-alive service is what stops Android from
 * reaping it, and it is told to stay up while any service runs.
 *
 * Cost when idle: one parked thread per service, blocked reading a pipe — no polling, no timers.
 */
class BackgroundServices(private val app: AlpineTermApp) {
    class Service(val id: String, val label: String, val session: PtySession) {
        val startedAt = System.currentTimeMillis()
        @Volatile var stopping = false
        private val log = StringBuilder()

        fun append(text: String) = synchronized(log) {
            log.append(text)
            if (log.length > MAX_LOG) log.delete(0, log.length - KEEP_LOG)
        }

        fun tail(chars: Int): String = synchronized(log) { log.takeLast(chars).toString() }
    }

    private val running = ConcurrentHashMap<String, Service>()
    private val starting = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "bg-service").apply { isDaemon = true } }

    /** Called (on any thread) whenever a service starts or ends; the UI uses it to redraw its list. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun isRunning(id: String): Boolean = running.containsKey(id) || starting.contains(id)

    fun hasActive(): Boolean = running.isNotEmpty() || starting.isNotEmpty()

    fun list(): List<Service> = running.values.sortedBy { it.startedAt }

    fun get(id: String): Service? = running[id]

    /** The last [chars] characters the service printed (ANSI colours removed), or "" when it is not running. */
    fun tail(id: String, chars: Int = 4000): String = running[id]?.tail(chars).orEmpty()

    /**
     * Starts [command] (guest shell code) as service [id]. Returns at once; the session starts on a worker thread.
     * [onFailed] gets a short reason when it could not start; [onExit] runs when it ends for any reason (a Stop, a
     * crash, Android killing it).
     */
    fun start(
        id: String,
        label: String,
        command: String,
        onExit: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null,
    ) {
        if (running.containsKey(id)) return
        if (!starting.add(id)) return // already starting
        pool.execute {
            val session = try {
                // fast = proot's seccomp tracing: a service runs for hours, so the cost per syscall matters most here.
                AlpineSession.startScript(app, command, mapOf("LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8"), fast = true)
            } catch (t: Throwable) { null }
            if (session == null) {
                starting.remove(id)
                onFailed?.invoke("Alpine is not ready yet — open a tab first")
                changed()
                return@execute
            }
            val svc = Service(id, label, session)
            running[id] = svc
            starting.remove(id)
            changed()
            drain(svc)
            // The program ended (Stop, crash, killed): tidy up exactly once.
            runCatching { session.destroy() }
            running.remove(id, svc)
            runCatching { onExit?.invoke() }
            changed()
        }
    }

    /** Reads the service's output until it ends — keeps the pty from filling up and blocking the program. */
    private fun drain(svc: Service) {
        runCatching {
            val reader = InputStreamReader(svc.session.stdout, Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                svc.append(ANSI.replace(String(buf, 0, n).replace("\r", ""), ""))
            }
        }
    }

    /** Stops service [id]: [PtySession.destroy] ends its whole process tree (and forces it after a short grace period). */
    fun stop(id: String) {
        val svc = running[id] ?: return
        svc.stopping = true
        runCatching { svc.session.destroy() }
    }

    fun stopAll() {
        running.keys.toList().forEach { stop(it) }
    }

    private fun changed() {
        // Nothing left to keep alive: let the foreground service (and its wake lock) go.
        if (app.tabs.isEmpty() && !hasActive() && !app.pluginJobs.hasActive()) {
            runCatching { app.stopService(Intent(app, TerminalKeepAliveService::class.java)) }
        }
        onChanged?.invoke()
    }

    private companion object {
        const val MAX_LOG = 64_000
        const val KEEP_LOG = 32_000
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
    }
}
