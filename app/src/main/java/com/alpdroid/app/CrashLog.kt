package com.alpdroid.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the stack trace of the last uncaught exception so the next start can show it. Android only tells an app
 * that its previous process "crashed" (ApplicationExitInfo), never why; without this a crash is a mystery.
 * Installed once from [AlpineTermApp.onCreate]; it records, then hands the exception on to the previous handler so
 * the process still dies exactly as before.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 6000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun format(threadName: String, error: Throwable, version: String, now: Long): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().take(MAX_CHARS)
        return "time=$now\nversion=$version\nthread=$threadName\n$trace"
    }

    private fun write(context: Context, threadName: String, error: Throwable) {
        val version = AppUpdater.currentVersion(context).let { "${it.first} (${it.second})" }
        File(context.filesDir, FILE).writeText(format(threadName, error, version, System.currentTimeMillis()))
    }

    /** The saved crash, if any; read once (deleted). Returned as readable text, newest crash only. */
    fun takeLast(context: Context): String? = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return@runCatching null
        val text = f.readText().take(MAX_CHARS + 200)
        f.delete()
        text
    }.getOrNull()
}
