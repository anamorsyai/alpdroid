package com.alpdroid.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads AlpBrowser's control-API connection details (token + the actually-bound control/proxy ports) directly from
 * the companion app, so the agent never has to copy the token by hand. Goes through AlpBrowser's
 * signature-protected ContentProvider — it only works because both apps are signed with the same key — and keeps
 * the guest's `/etc/alpdroid/alpbrowser.env` (which `browserctl` parses) up to date.
 *
 * Two things make this safe to call from session start and from the UI:
 *  - a provider call COLD-STARTS AlpBrowser's process when it is not running and blocks on the binder until its
 *    Application is up, with no timeout of its own — so [query] runs on its own thread under a hard deadline and
 *    never lets a slow or hung AlpBrowser stall the caller (a session start used to wait on it);
 *  - [refreshEnv] only asks when the answer can have changed — the file is missing, AlpBrowser was installed or
 *    updated after it was written, or it has gone stale — so most tab starts cost a couple of `stat` calls.
 */
object BrowserLink {
    data class Info(val token: String, val controlPort: Int, val proxyPort: Int)

    // Lazy: Uri.parse is an Android API, and this object's pure helpers are unit-tested on a plain JVM.
    private val authorityUri: Uri by lazy { Uri.parse("content://com.alpdroid.browser.control") }

    /** How long a session start (or resume) is willing to wait for AlpBrowser to answer. */
    private const val QUERY_DEADLINE_MS = 1500L

    /** Re-ask at least this often even when nothing seems to have changed: the control port can differ per run. */
    private const val MAX_AGE_MS = 10L * 60 * 1000

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "alpdroid-browserlink").apply { isDaemon = true } }

    /** A query whose binder call is still stuck: further queries are skipped (not queued behind it) until it returns. */
    private val inFlight = AtomicBoolean(false)

    /** Runs [refreshEnv] off every caller's thread. Separate from [executor]: a refresh waits on a query, and a
     *  single thread waiting on its own queue would deadlock. */
    private val refresher = Executors.newSingleThreadExecutor { r -> Thread(r, "alpdroid-browserlink-refresh").apply { isDaemon = true } }
    private val refreshQueued = AtomicBoolean(false)

    /** Null when AlpBrowser is not installed, its provider is unreachable, did not answer within the deadline, or has not started yet. */
    fun query(context: Context, deadlineMs: Long = QUERY_DEADLINE_MS): Info? {
        if (!inFlight.compareAndSet(false, true)) return null
        val app = context.applicationContext
        val future = try {
            executor.submit(Callable { try { queryNow(app) } finally { inFlight.set(false) } })
        } catch (e: Exception) { inFlight.set(false); return null }
        return try { future.get(deadlineMs, TimeUnit.MILLISECONDS) }
        catch (e: Exception) { future.cancel(true); null }
    }

    private fun queryNow(context: Context): Info? = runCatching {
        val b = context.contentResolver.call(authorityUri, "info", null, null) ?: return null
        val token = b.getString("token")?.takeIf { it.isNotBlank() } ?: return null
        Info(token, b.getInt("controlPort", 8899), b.getInt("proxyPort", 8890))
    }.getOrNull()

    /** The env-file text `browserctl` parses. Pure, so it is unit-tested. */
    internal fun envText(info: Info): String =
        "ALPBROWSER_TOKEN=${info.token}\nALPBROWSER_PORT=${info.controlPort}\nALPBROWSER_PROXY=${info.proxyPort}\n"

    /**
     * Whether [envFile] needs rewriting: it is missing, AlpBrowser was installed/updated after it was written
     * (the token or ports may be new), or it is older than [MAX_AGE_MS]. Pure, so it is unit-tested.
     */
    internal fun needsRefresh(envExists: Boolean, envModifiedMs: Long, browserUpdatedMs: Long, nowMs: Long): Boolean =
        !envExists || envModifiedMs < browserUpdatedMs || nowMs - envModifiedMs > MAX_AGE_MS

    /**
     * [refreshEnv] without ever making the caller wait — for session start and for UI callbacks. Requests that
     * arrive while one is already queued collapse into it. `browserctl` re-reads the file on every call, so a
     * refresh that lands a moment after a tab opened still reaches that tab.
     */
    fun refreshEnvAsync(context: Context, envFile: File) {
        if (!refreshQueued.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            refresher.execute {
                refreshQueued.set(false)
                runCatching { refreshEnv(app, envFile) }
            }
        } catch (e: Exception) { refreshQueued.set(false) }
    }

    /**
     * Brings [envFile] up to date. No-op when AlpBrowser is not installed (the file is removed so `browserctl` says
     * "not linked" rather than using a dead token) or when the file is still fresh. A miss (AlpBrowser still
     * starting, deadline hit) leaves any existing good file untouched. The write is atomic — a temp file renamed
     * into place — so a `browserctl` running at that instant never reads a half-written file.
     */
    fun refreshEnv(context: Context, envFile: File) {
        if (!BrowserInstall.isInstalled(context)) { envFile.delete(); return }
        val now = System.currentTimeMillis()
        if (!needsRefresh(envFile.isFile, envFile.lastModified(), BrowserInstall.lastUpdateTime(context), now)) return
        val info = query(context) ?: return
        runCatching {
            envFile.parentFile?.mkdirs()
            val tmp = File(envFile.parentFile, envFile.name + ".tmp")
            tmp.writeText(envText(info))
            tmp.setReadable(true, true); tmp.setWritable(true, true); tmp.setExecutable(false, false)
            if (!tmp.renameTo(envFile)) { tmp.delete() }
        }
    }
}
