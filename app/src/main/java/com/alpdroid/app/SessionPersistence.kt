package com.alpdroid.app

import android.content.Context
import org.json.JSONArray

/**
 * Remembers only *how many* tabs were open and their user-assigned labels — not their working
 * directory, environment, or shell history, since none of that survives the actual Linux
 * process being killed along with the app. When Android fully kills the process (rather than
 * just backgrounding it) a coding agent or shell mid-run dies either way; this just saves
 * re-creating the same number of named tabs by hand afterward, offered once on the next cold
 * start rather than silently auto-recreated.
 */
object SessionPersistence {
    fun save(context: Context, labels: List<String?>) {
        val array = JSONArray()
        labels.forEach { array.put(it) }
        // commit(): tiny payload, and apply() risks losing it on background-then-kill.
        prefs(context).edit().putString(KEY_LABELS, array.toString()).commit()
    }

    fun load(context: Context): List<String?> {
        val raw = prefs(context).getString(KEY_LABELS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { i -> if (array.isNull(i)) null else array.optString(i) }
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY_LABELS).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("alpineterm_last_session", Context.MODE_PRIVATE)

    private const val KEY_LABELS = "labels"
}
