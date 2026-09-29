package com.alpdroid.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved SSH destination — never a password, just enough to build the `ssh` command line so
 *  reconnecting from a phone is one tap instead of re-typing host/port/user each time. */
data class SshProfile(val name: String, val host: String, val port: String, val user: String) {
    fun connectCommand(): String {
        val portArg = if (port.isNotBlank() && port != "22") "-p $port " else ""
        val userHost = if (user.isNotBlank()) "$user@$host" else host
        return "ssh $portArg$userHost\n"
    }
}

object SshProfiles {
    fun list(context: Context): List<SshProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            SshProfile(o.optString("name"), o.optString("host"), o.optString("port"), o.optString("user"))
        }
    }

    fun add(context: Context, profile: SshProfile) = save(context, list(context) + profile)

    fun remove(context: Context, profile: SshProfile) = save(context, list(context) - profile)

    private fun save(context: Context, profiles: List<SshProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply { put("name", p.name); put("host", p.host); put("port", p.port); put("user", p.user) })
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("alpineterm_ssh_profiles", Context.MODE_PRIVATE)

    private const val KEY_PROFILES = "profiles"
}
