package com.alpdroid.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Tells the user why Android last stopped AlpDroid. When the app comes back with a fresh splash and an
 * empty tab after being left in the background, every session died with the process — and nothing said
 * why. Android 11+ keeps the reason (low memory, a kill signal from the phone maker's battery manager,
 * a crash …); this reads it once at the next start and explains it, with what can be done about it.
 */
object ExitReport {
    private const val PREFS = "alpineterm_exit_report"
    private const val KEY_LAST_TS = "last_ts"

    // ApplicationExitInfo.REASON_* values (API 30+), spelled out so the text builder stays unit-testable.
    private const val REASON_EXIT_SELF = 1
    private const val REASON_SIGNALED = 2
    private const val REASON_LOW_MEMORY = 3
    private const val REASON_CRASH = 4
    private const val REASON_CRASH_NATIVE = 5
    private const val REASON_ANR = 6
    private const val REASON_PERMISSION_CHANGE = 8
    private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    private const val REASON_USER_REQUESTED = 10
    private const val REASON_USER_STOPPED = 11
    private const val REASON_FREEZER = 14
    private const val REASON_PACKAGE_STATE_CHANGE = 15
    private const val REASON_PACKAGE_UPDATED = 16

    private val BENIGN = setOf(REASON_EXIT_SELF, REASON_USER_REQUESTED, REASON_USER_STOPPED, REASON_PERMISSION_CHANGE, REASON_PACKAGE_STATE_CHANGE, REASON_PACKAGE_UPDATED)

    private fun importanceText(importance: Int): String = when {
        importance <= 100 -> "while it was on screen"
        importance <= 125 -> "while its keep-alive service was running"
        importance <= 230 -> "while it was only partly visible"
        importance <= 300 -> "while it ran as a background service"
        else -> "while it was cached in the background (the keep-alive service was not holding it)"
    }

    /** The explanation for one exit, or null when the exit was the user's own doing. */
    fun describe(reason: Int, importance: Int, rssKb: Long, description: String?): String? {
        if (reason in BENIGN) return null
        val what = when (reason) {
            REASON_LOW_MEMORY -> "Android ran low on memory and closed AlpDroid together with everything running in it."
            REASON_SIGNALED -> "Something sent AlpDroid a kill signal — usually the phone maker's battery or memory manager."
            REASON_EXCESSIVE_RESOURCE_USAGE -> "Android stopped AlpDroid for using too much CPU or memory in the background."
            REASON_CRASH, REASON_CRASH_NATIVE -> "AlpDroid crashed."
            REASON_ANR -> "AlpDroid stopped responding and was closed."
            REASON_FREEZER -> "Android froze AlpDroid while it was cached in the background."
            else -> "Android closed AlpDroid."
        }
        val advice = when (reason) {
            REASON_LOW_MEMORY -> "Programs such as opencode use hundreds of MB. Close other apps, run fewer things at once, or lower Settings → Sessions & Background → Terminal memory."
            REASON_EXCESSIVE_RESOURCE_USAGE ->
                "A program inside AlpDroid kept the CPU far busier than Android allows a background app (about 25% on average over 5 minutes). " +
                    "Settings → Usage shows which process is the busiest; keep \"Faster process tracing\" on, run fewer agents at once, or keep AlpDroid open while a heavy job runs."
            REASON_SIGNALED, REASON_FREEZER ->
                "Allow AlpDroid to run in the background and start by itself in the phone's battery / app-launch settings, lock it in the recent-apps list, and keep \"Keep sessions alive in background\" on."
            REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR -> "If it keeps happening, please report it with what you were doing."
            else -> "Keep \"Keep sessions alive in background\" on and exempt AlpDroid from battery optimisation."
        }
        val sb = StringBuilder(what)
        sb.append("\n\nIt happened ").append(importanceText(importance))
        if (rssKb > 0) sb.append(" (using about ").append(rssKb / 1024).append(" MB)")
        sb.append(". All its sessions ended with it.")
        if (!description.isNullOrBlank()) sb.append("\n\nAndroid says: ").append(description.take(200))
        sb.append("\n\n").append(advice)
        return sb.toString()
    }

    /** Message to show for an unreported abnormal exit of the previous process, or null. Marks it reported. */
    fun checkOnStart(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val newest = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .firstOrNull { it.processName == context.packageName } ?: return@runCatching null
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_LAST_TS, 0L)
            prefs.edit().putLong(KEY_LAST_TS, newest.timestamp).apply()
            if (newest.timestamp <= seen) return@runCatching null
            val text = describe(newest.reason, newest.importance, newest.rss, newest.description)
            // A crash: attach the stack trace CrashLog saved (the system only says "crash").
            val isCrash = newest.reason == REASON_CRASH || newest.reason == REASON_CRASH_NATIVE
            val trace = if (isCrash) CrashLog.takeLast(context) else null
            if (text != null && trace != null) "$text\n\n── Crash details ──\n$trace" else text
        }.getOrNull()
    }
}
