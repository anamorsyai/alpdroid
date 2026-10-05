package com.alpdroid.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved SSH destination — never a password, just enough to build the `ssh` command line so
 *  reconnecting from a phone is one tap instead of re-typing host/port/user each time. */
data class SshProfile(val name: String, val host: String, val port: String, val user: String) {
    fun connectCommand(): String {
        // Single-quote every field: profile fields are typed by the user and interpolated
        // straight into a shell command line, so `host="x; rm -rf ~"` would otherwise inject.
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val portArg = port.toIntOrNull()?.takeIf { it in 1..65535 && it != 22 }?.let { "-p $it " } ?: ""
        val userHost = if (user.isNotBlank()) "${q(user)}@${q(host)}" else q(host)
        // "--" ends the options: a host or user starting with "-" must never be read as an ssh option
        // (for example -oProxyCommand=...).
        return "ssh $portArg-- $userHost\n"
    }
}

object SshProfiles {
    @Volatile private var cache: List<SshProfile>? = null
    // Synchronized: add/remove are read-modify-write (list + save); two threads
    // interlacing them would silently drop a profile. All current callers are UI,
    // so this is free insurance, not a hot path.
    @Synchronized fun list(context: Context): List<SshProfile> = cache ?: run {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            SshProfile(o.optString("name"), o.optString("host"), o.optString("port"), o.optString("user"))
        }.also { cache = it }
    }

    @Synchronized fun add(context: Context, profile: SshProfile) = save(context, list(context) + profile)

    @Synchronized fun remove(context: Context, profile: SshProfile) = save(context, list(context) - profile)

    private fun save(context: Context, profiles: List<SshProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply { put("name", p.name); put("host", p.host); put("port", p.port); put("user", p.user) })
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
        cache = profiles
    }

    private fun prefs(context: Context) = context.getSharedPreferences("alpineterm_ssh_profiles", Context.MODE_PRIVATE)

    private const val KEY_PROFILES = "profiles"
}
