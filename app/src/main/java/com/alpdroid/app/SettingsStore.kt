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

    /** Experimental. proot's seccomp acceleration for new interactive sessions: much less CPU for
     *  syscall-heavy programs, but some `apk` operations can fail with EPERM, so it is opt-in. */
    var fastProotTracing: Boolean
        get() = prefs.getBoolean(KEY_FAST_TRACING, false)
        set(value) = prefs.edit().putBoolean(KEY_FAST_TRACING, value).apply()

    /** Pins new interactive sessions to the phone's low-power cores (see CpuTopology): a program that
     *  spins a core while idle then runs much cooler, at the cost of peak speed. Off by default. */
    var efficiencyCores: Boolean
        get() = prefs.getBoolean(KEY_EFFICIENCY_CORES, false)
        set(value) = prefs.edit().putBoolean(KEY_EFFICIENCY_CORES, value).apply()

    /** Dynamic load balancing (see LoadBalancer): busy sessions nobody is looking at are parked on the
     *  low-power cores so the foreground tab and other apps keep the big ones. On by default; it only
     *  acts on phones where the low-power cores can be identified. */
    var smartBalancing: Boolean
        get() = prefs.getBoolean(KEY_SMART_BALANCING, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_BALANCING, value).apply()

    /** The smart resource manager (see ResourceManager): adapts repaint rate to heat/battery, trims
     *  memory under pressure, closes frozen sessions. On by default. */
    var resourceManagerEnabled: Boolean
        get() = prefs.getBoolean(KEY_RESOURCE_MANAGER, true)
        set(value) = prefs.edit().putBoolean(KEY_RESOURCE_MANAGER, value).apply()

    /** Terminal history kept per tab. Every cell is an object, so a tab's scrollback costs real
     *  memory (thousands of lines add up to megabytes); 1000 keeps it light by default. Applies
     *  to tabs opened after the change. */
    var scrollbackLines: Int
        get() = prefs.getInt(KEY_SCROLLBACK, 1000).coerceIn(SCROLLBACK_OPTIONS.first(), SCROLLBACK_OPTIONS.last())
        set(value) = prefs.edit().putInt(KEY_SCROLLBACK, value).apply()

    /** Whether the one-time "exempt from battery optimization" prompt was already shown. */
    var batteryPromptShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, value).apply()

    /** On by default: a coding agent (opencode, Claude Code, Cline, ...) or a build running
     *  unattended in the background is exactly the case this exists for. */
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    /** On by default: without it the CPU/Wi-Fi sleep with the screen off even under a foreground
     *  service and a battery exemption, which stalls servers and long operations. Only held while
     *  sessions/jobs exist (the service stops otherwise); costs battery, so it stays toggleable. */
    var wakeLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_LOCK, value).apply()

    /** Off by default. When on, a backup is taken at app start if the last automatic one is more
     *  than a week old, keeping only the newest few. */
    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BACKUP, value).apply()

    var lastAutoBackupMs: Long
        get() = prefs.getLong(KEY_LAST_AUTO_BACKUP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_BACKUP, value).apply()

    /** Last finished update check (found or not) — the daily auto-check's throttle. */
    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** Last moment the app was provably alive (onPause + periodic refresh). 0 = clean start
     *  or deliberate exit. A stale value on launch means the system killed the process. */
    var lastAliveMs: Long
        get() = prefs.getLong(KEY_LAST_ALIVE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_ALIVE, value).commit()
        }

    /** True when the last onDestroy ran (swipe-away, rotation, Back) — as opposed to a kill,
     *  which runs no lifecycle at all. Distinguishes "user closed it" from "system killed it". */
    var destroyWasClean: Boolean
        get() = prefs.getBoolean(KEY_DESTROY_CLEAN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESTROY_CLEAN, value).commit()
        }

    /** Off by default: lets programs inside the terminal call this app's local control API
     *  (see AgentBridge) — the user-approved "agent access" feature. */
    var agentAccessEnabled: Boolean
        get() = prefs.getBoolean(KEY_AGENT, false)
        set(value) = prefs.edit().putBoolean(KEY_AGENT, value).apply()

    /** On by default: writes a short note about this environment into the standard files coding
     *  agents read on their own (AGENTS.md, CLAUDE.md, ...), between markers so anything the user
     *  wrote in those files is left alone. */
    var agentContextFiles: Boolean
        get() = prefs.getBoolean(KEY_AGENT_CTX, true)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_CTX, value).apply()

    /** Whether the control API may hand the stored GitHub token to programs in the terminal. */
    var agentGithubToken: Boolean
        get() = prefs.getBoolean(KEY_AGENT_GH, false)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_GH, value).apply()

    /** Random per install; rotating it locks out anything holding the old one. Synchronized:
     *  two threads racing the first read used to mint two different tokens and persist only
     *  the second, silently invalidating whatever the first caller was already using. */
    val agentToken: String
        @Synchronized get() = prefs.getString(KEY_AGENT_TOKEN, null) ?: regenerateAgentToken()

    @Synchronized
    fun regenerateAgentToken(): String {
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        val token = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        // commit(), not apply(): a process kill before the async flush would lose the minted
        // token and the next launch would mint a different one.
        prefs.edit().putString(KEY_AGENT_TOKEN, token).commit()
        return token
    }

    var githubClientId: String
        get() = prefs.getString(KEY_GH_CLIENT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GH_CLIENT, value.trim()).apply()

    /** Password protecting the LAN-exposed opencode web server (user "opencode"). */
    val opencodeWebPassword: String
        @Synchronized get() = prefs.getString(KEY_OC_PASS, null) ?: run {
            val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = java.security.SecureRandom()
            val pw = (1..20).map { chars[r.nextInt(chars.length)] }.joinToString("")
            // commit(): the shown password must match what's on disk even if killed here.
            prefs.edit().putString(KEY_OC_PASS, pw).commit()
            pw
        }

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
    @Volatile private var snippetsCache: List<Pair<String, String>>? = null
    var customSnippets: List<Pair<String, String>>
        get() = snippetsCache ?: run {
            val raw = prefs.getString(KEY_SNIPPETS, null) ?: return emptyList()
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    obj.getString("label") to obj.getString("cmd")
                }
            }.getOrDefault(emptyList()).also { snippetsCache = it }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { (label, cmd) ->
                arr.put(org.json.JSONObject().apply { put("label", label); put("cmd", cmd) })
            }
            prefs.edit().putString(KEY_SNIPPETS, arr.toString()).apply()
            snippetsCache = value
        }

    /** One-time: the extra-keys row now has built-in "alphacode" and "claude" keys, so a
     *  user-made "AlphaCode monitor" shortcut is dropped instead of sitting next to them. Matches
     *  by label (case-insensitive), by the exact command `alphacode monitor`, or by a command
     *  running monitor_alpine.sh — nothing else is touched. (v2: v1 only matched the exact
     *  command and missed shortcuts that run the monitor script.) */
    fun migrateLegacyAlphacodeShortcut() {
        if (prefs.getBoolean(KEY_SNIPPET_MIG_ALPHACODE, false)) return
        val before = customSnippets
        val kept = before.filterNot { (label, cmd) ->
            val c = cmd.trim()
            label.trim().equals("alphacode monitor", ignoreCase = true) ||
                c.equals("alphacode monitor", ignoreCase = true) ||
                c.contains("monitor_alpine.sh")
        }
        if (kept.size != before.size) customSnippets = kept
        prefs.edit().putBoolean(KEY_SNIPPET_MIG_ALPHACODE, true).apply()
    }

    /** One-time: the row's built-in "alphacode" and "claude" keys were removed — those launchers are ordinary
     *  custom shortcuts now (Settings → Display → Custom shortcuts), so they can be edited or deleted. Existing
     *  users keep their buttons: both are added once as shortcuts unless a shortcut with that label (any case)
     *  already exists. The local-proxy restart that the old `claude` key ran first is gone with the shim. */
    fun migrateEmbeddedKeysToShortcuts() {
        if (prefs.getBoolean(KEY_SNIPPET_MIG_EMBEDDED, false)) return
        val current = customSnippets
        val additions = listOf(
            "alphacode" to "alphacode\n",
            // proot's fake root breaks Claude Code's cross-session-messaging uid check; an explicit per-tab socket
            // path in a private (0700) directory avoids it.
            "claude" to "mkdir -p /root/.claude/run && chmod 700 /root/.claude/run; claude --messaging-socket-path /root/.claude/run/msg-\$\$.sock\n",
        ).filter { (label, _) -> current.none { it.first.trim().equals(label, ignoreCase = true) } }
        if (additions.isNotEmpty()) customSnippets = current + additions
        prefs.edit().putBoolean(KEY_SNIPPET_MIG_EMBEDDED, true).apply()
    }

    companion object {
        val SCROLLBACK_OPTIONS = listOf(500, 1000, 2000, 5000)
        private const val KEY_SCROLLBACK = "scrollback_lines"
        private const val KEY_RESOURCE_MANAGER = "resource_manager_enabled"
        private const val KEY_FAST_TRACING = "fast_proot_tracing"
        private const val KEY_EFFICIENCY_CORES = "efficiency_cores"
        private const val KEY_SMART_BALANCING = "smart_balancing"
        private const val KEY_SNIPPET_MIG_ALPHACODE = "snippet_mig_alphacode_monitor_v2"
        private const val KEY_SNIPPET_MIG_EMBEDDED = "snippet_mig_embedded_keys_v1"
        private const val KEY_THEME = "theme"
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_EXTRA_KEYS = "show_extra_keys"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        private const val KEY_WAKE_LOCK = "wake_lock_enabled"
        private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
        private const val KEY_AGENT = "agent_access"
        private const val KEY_AGENT_CTX = "agent_context_files"
        private const val KEY_AGENT_GH = "agent_github_token"
        private const val KEY_AGENT_TOKEN = "agent_token"
        private const val KEY_GH_CLIENT = "github_client_id"
        private const val KEY_OC_PASS = "opencode_web_password"
        private const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup_ms"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check_ms"
        private const val KEY_LAST_ALIVE = "last_alive_ms"
        private const val KEY_DESTROY_CLEAN = "destroy_was_clean"
        private const val KEY_LIGATURES = "ligatures_enabled"
        private const val KEY_BELL_SOUND = "bell_sound_enabled"
        private const val KEY_SNIPPETS = "custom_snippets"
    }
}
