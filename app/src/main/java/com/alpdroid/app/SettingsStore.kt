package com.alpdroid.app

import android.content.Context
import org.json.JSONArray

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("alpineterm_settings", Context.MODE_PRIVATE)

    var themeId: String
        get() = prefs.getString(KEY_THEME, "alpine") ?: "alpine"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 15f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var showExtraKeys: Boolean
        get() = prefs.getBoolean(KEY_EXTRA_KEYS, true)
        set(value) = prefs.edit().putBoolean(KEY_EXTRA_KEYS, value).apply()

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "monospace") ?: "monospace"
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    /** On by default: a coding agent (opencode, Claude Code, Cline, ...) or a build running
     *  unattended in the background is exactly the case this exists for. */
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    /** Off by default — a real, ongoing battery cost, unlike the foreground service alone. */
    var wakeLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_LOCK, value).apply()

    /** Off by default. When on, a backup is taken at app start if the last automatic one is more
     *  than a week old, keeping only the newest few. */
    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BACKUP, value).apply()

    var lastAutoBackupMs: Long
        get() = prefs.getLong(KEY_LAST_AUTO_BACKUP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_BACKUP, value).apply()

    /** On by default — Fira Code is bundled specifically for its ligatures. */
    var ligaturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIGATURES, true)
        set(value) = prefs.edit().putBoolean(KEY_LIGATURES, value).apply()

    /** Off by default: a distinct, audible cue for the terminal bell independent of the vibrate/
     *  notification path — for someone who keeps the phone on silent but still wants to notice a
     *  finished build or an agent waiting on input without watching the screen. */
    var bellSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_BELL_SOUND, false)
        set(value) = prefs.edit().putBoolean(KEY_BELL_SOUND, value).apply()

    /** User-defined extra-keys-row shortcuts (label to literal command text, e.g. "git status" ->
     *  "git status\n") — stored as a JSON array of {"label","cmd"} objects since SharedPreferences
     *  has no native list type. Order is preserved (JSONArray, not a Set) so the row always shows
     *  them in the order they were added. */
    var customSnippets: List<Pair<String, String>>
        get() {
            val raw = prefs.getString(KEY_SNIPPETS, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    obj.getString("label") to obj.getString("cmd")
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { (label, cmd) ->
                arr.put(org.json.JSONObject().apply { put("label", label); put("cmd", cmd) })
            }
            prefs.edit().putString(KEY_SNIPPETS, arr.toString()).apply()
        }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_EXTRA_KEYS = "show_extra_keys"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        private const val KEY_WAKE_LOCK = "wake_lock_enabled"
        private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup_ms"
        private const val KEY_LIGATURES = "ligatures_enabled"
        private const val KEY_BELL_SOUND = "bell_sound_enabled"
        private const val KEY_SNIPPETS = "custom_snippets"
    }
}
