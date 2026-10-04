package com.alpdroid.app

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Spawns [libraryDir]/libpty_bridge.so (see app/src/main/cpp/pty_bridge.c), which gives [argv]
 * a real PTY as its controlling terminal — something no public Android/JVM API can set up
 * directly (no forkpty(), no pre-exec hook on ProcessBuilder) — and relays bytes between that
 * PTY and this process's own stdin/stdout, which is all a plain ProcessBuilder subprocess
 * actually gives us.
 *
 * Window resizes go over a separate named-pipe control channel rather than the data stream, so
 * a resize can never be misread as terminal input/output.
 */
class PtySession private constructor(
    private val process: Process,
    private val controlFifo: File,
) {
    val stdout: InputStream = process.inputStream
    val stdin: OutputStream = process.outputStream

    /** Scratch dir (proot PROOT_TMP_DIR) owned by this session, deleted on destroy(). Set by
     *  AlpineSession right after start(); previously these accumulated in cacheDir forever
     *  (only the package-search path cleaned up after itself). */
    var cleanupDir: File? = null

    /** One executor per session, not one shared across every tab (MainActivity used to route
     *  every tab's writeToSession() through a single app-wide executor) — a write that blocks
     *  (a large paste into a program that isn't reading its stdin fast enough, or one whose pty
     *  itself is backed up) used to stall keystrokes to every OTHER tab too, not just the one
     *  actually stuck, since they all queued behind the same single thread. Daemon threads so a
     *  missed destroy() can never pin the whole process alive on its own. */
    private val writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pty-write").apply { isDaemon = true } }

    fun writeAsync(bytes: ByteArray) {
        // Guard: execute() after destroy()'s shutdownNow() throws RejectedExecutionException
        // synchronously — a late IME keystroke racing tab close must be dropped, not crash.
        runCatching { if (!writeExecutor.isShutdown) writeExecutor.execute {
            runCatching { stdin.write(bytes); stdin.flush() }
        } }
    }

    @Volatile
    private var controlOut: OutputStream? = null

    // resize() only ever records the *latest* requested size here and wakes resizeThread —
    // it never queues a write per call. A plain single-thread executor (the previous approach)
    // queues one write per resize() call in order: harmless for one resize at a time, but a
    // burst of several calls in quick succession (an IME animation driving several intermediate
    // row counts, or a run of taps on the extra-keys row) used to queue a backlog of writes that
    // the native side works through one at a time — including sizes already superseded by the
    // time they're actually delivered. The shell reads its window size (LINES/COLUMNS, via
    // SIGWINCH) from whichever ioctl(TIOCSWINSZ) most recently landed, so while that backlog was
    // draining, the shell's idea of the terminal size could be several resizes behind our own
    // TerminalEmulator model, which updates synchronously and instantly. Any shell output in that
    // window (readline redrawing the current line on Up/Down history recall, in particular) gets
    // wrapped/positioned against the wrong width — misdrawing in a way that looks exactly like a
    // line vanishing, despite nothing actually being lost in either buffer. Coalescing to "always
    // send only the newest pending size, as soon as possible" collapses any such burst down to
    // one write and keeps the gap between our model and the shell's as small as it can be.
    private var pendingRows = -1
    private var pendingCols = -1
    private val resizeLock = Object()
    @Volatile private var resizeThreadRunning = true

    private val resizeThread = Thread({
        var lastRows = -1
        var lastCols = -1
        while (resizeThreadRunning) {
            val (rows, cols) = synchronized(resizeLock) {
                while (resizeThreadRunning && pendingRows == lastRows && pendingCols == lastCols) {
                    resizeLock.wait()
                }
                pendingRows to pendingCols
            }
            if (!resizeThreadRunning) break
            val out = controlOut
            if (out == null) {
                // Control channel never opened (bridge failed to start): resize is a permanent
                // no-op, so stop instead of polling every 5ms until destroy().
                if (controlOpenFailed) break
                // Otherwise only the brief window right after start() before
                // openControlChannelAsync() finishes opening the FIFO — back off briefly and
                // re-check the same target size.
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }
            // Always advances past this size, success or failure — a write failing here means the
            // pipe/process is gone (EPIPE), which is permanent, not transient; leaving
            // lastRows/lastCols stale on failure used to make the synchronized wait() condition
            // above false forever (pendingRows/Cols never re-equal lastRows/Cols), so the loop
            // never actually waited again and just retried the same doomed write as fast as the
            // CPU could spin, forever, until destroy() finally caught up.
            runCatching { out.write("$rows $cols\n".toByteArray()); out.flush() }
            lastRows = rows
            lastCols = cols
        }
    }, "pty-resize").apply { isDaemon = true }.also { it.start() }

    /** Set once destroy() runs — checked right after the FIFO open below finishes, in case that
     *  open (which can legitimately take a little while; see its own doc comment) is still in
     *  flight when destroy() is called. Without this, an open that completes after destroy() has
     *  already deleted the FIFO file would still assign a live, now-orphaned file descriptor to
     *  controlOut — never closed by anything, since destroy() (the only thing that closes it) has
     *  already run and won't run again for this session. */
    @Volatile private var destroyed = false

    /** Set when the FIFO open gave up after its retry window — resizeThread exits on it. */
    @Volatile private var controlOpenFailed = false

    /** Opens the write end of the resize FIFO on a background thread. A plain blocking
     *  FileOutputStream(controlFifo) here would wait forever for pty_bridge's own read end to
     *  exist — normally microseconds, but if the bridge process fails to start (or crashes before
     *  ever opening its end), that wait never ends, leaking this thread for as long as the app
     *  runs. Opened non-blocking instead (which fails immediately with ENXIO rather than blocking
     *  when no reader exists yet) and retried with a short backoff, bounded to a few seconds —
     *  comfortably past the "microseconds" the bridge normally takes, but not forever. Safe to
     *  call once, right after [start]. */
    fun openControlChannelAsync() {
        Thread({
            val deadline = System.currentTimeMillis() + 3000
            while (!destroyed) {
                val opened = runCatching {
                    val fd = Os.open(controlFifo.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
                    FileOutputStream(fd)
                }
                if (opened.isSuccess) {
                    val stream = opened.getOrThrow()
                    if (destroyed) {
                        // destroy() ran while this open was in flight — this fd would otherwise
                        // never be closed by anything.
                        runCatching { stream.close() }
                    } else {
                        controlOut = stream
                    }
                    return@Thread
                }
                if (System.currentTimeMillis() >= deadline) {
                    Log.w(TAG, "control channel unavailable after retrying; resize will be a no-op", opened.exceptionOrNull())
                    controlOpenFailed = true
                    synchronized(resizeLock) { resizeLock.notifyAll() }
                    return@Thread
                }
                Thread.sleep(20)
            }
        }, "pty-control-open").apply { isDaemon = true }.start()
    }

    fun resize(rows: Int, cols: Int) {
        synchronized(resizeLock) {
            pendingRows = rows
            pendingCols = cols
            resizeLock.notifyAll()
        }
    }

    fun isAlive(): Boolean = runCatching { process.exitValue(); false }.getOrDefault(true)

    /** The bridge binary's own stderr is never part of the terminal stream (redirectErrorStream
     *  stays false so pty output framing is untouched) — but an undrained pipe fills up and
     *  wedges the child once the OS buffer is full. A daemon thread discards it; the bridge is
     *  quiet on success, so this costs nothing in the common case. */
    private fun drainStderr() {
        Thread({
            runCatching {
                val buf = ByteArray(1024)
                while (process.errorStream.read(buf) != -1) { /* discard */ }
            }
        }, "pty-stderr-drain").apply { isDaemon = true; start() }
    }

    /** Blocks (the calling thread, never the main one — callers are expected to be on a
     *  background executor) until this session's process has actually exited, or [timeoutMs]
     *  passes. destroy() only *requests* the process die; a caller that immediately starts
     *  overwriting files the process might still be touching (a restore/reinstall replacing the
     *  whole rootfs right after closing every tab) needs this instead of assuming destroy() was
     *  synchronous. */
    fun awaitExit(timeoutMs: Long): Boolean =
        runCatching { process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(true)

    fun destroy() {
        destroyed = true
        runCatching { process.destroy() }
        // The guest tree (proot + shell + daemons) is the bridge's child subtree: destroy()
        // SIGTERMs the bridge, whose handler SIGKILLs that whole process group — but a wedged
        // bridge must never pin a leaked guest. Re-check after a grace period and force.
        // Daemon thread: destroy() is called from UI and reader threads alike, never blocks.
        Thread({
            runCatching { process.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS) }
            if (runCatching { process.exitValue() }.isFailure) runCatching { process.destroyForcibly() }
        }, "pty-destroy").apply { isDaemon = true; start() }
        // shutdownNow() (not shutdown()) — a write that's currently blocked (the exact scenario
        // this executor exists to isolate from other tabs) should be interrupted right away along
        // with the rest of the session tearing down, not left to drain on its own.
        writeExecutor.shutdownNow()
        // The process's own pipes: destroying the process doesn't close our ends, leaking an
        // fd trio per closed tab until the app process itself dies.
        runCatching { stdin.close() }
        runCatching { stdout.close() }
        runCatching { process.errorStream.close() }
        runCatching { controlOut?.close() }
        runCatching { controlFifo.delete() }
        resizeThreadRunning = false
        synchronized(resizeLock) { resizeLock.notifyAll() }
        // Owned scratch dir (set by AlpineSession): the guest is SIGTERMed above, so its
        // tmp use is over; never let cache accumulate dead proot dirs across tabs/jobs.
        // NoFollow: guest-planted symlinks inside must not resolve outward on delete.
        runCatching { cleanupDir?.deleteRecursivelyNoFollow() }
    }

    companion object {
        private const val TAG = "AlpDroid/Pty"

        fun start(
            bridgeBinary: File,
            controlFifoDir: File,
            rows: Int,
            cols: Int,
            command: List<String>,
            workingDirectory: File,
            env: Map<String, String>,
        ): PtySession {
            // UUID, not nanoTime (which restarts on reboot and can theoretically collide for
            // two rapid tabs) — plus an existence check so a squatted path fails loudly.
            val fifo = File(controlFifoDir, "pty-ctl-${java.util.UUID.randomUUID()}.fifo")
            if (fifo.exists()) throw IllegalStateException("control fifo already exists")
            fifo.delete()
            Os.mkfifo(fifo.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)

            val argv = mutableListOf(bridgeBinary.absolutePath, fifo.absolutePath, rows.toString(), cols.toString(), "--")
            argv.addAll(command)

            val builder = ProcessBuilder(argv)
            builder.directory(workingDirectory)
            builder.environment().clear()
            builder.environment().putAll(env)
            builder.redirectErrorStream(false)
            val process: Process
            try {
                process = builder.start()
            } catch (e: Exception) {
                // start() throwing (missing/ broken bridge binary) used to leave the mkfifo'd
                // control file behind in the cache dir, forever, once per attempt.
                runCatching { fifo.delete() }
                throw e
            }
            val session = PtySession(process, fifo)
            session.openControlChannelAsync()
            session.drainStderr()
            return session
        }
    }
}
