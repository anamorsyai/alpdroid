package com.alpdroid.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Plugins: a folder under the guest's ~/.alpdroid/plugins/<id>/ holding a plugin.json (the fields
 * and buttons to show) plus scripts (the logic, run inside Alpine when a button is pressed). They
 * live inside the Alpine filesystem, so the existing backup/restore carries them — and each
 * plugin's saved field values (state.json) — with nothing extra. Nothing here loads compiled code:
 * the UI is built from the JSON, and the logic is plain shell run in the sandboxed guest.
 */
object Plugins {
    data class Field(val id: String, val type: String, val label: String, val default: String, val options: List<String>)
    data class Button(val id: String, val label: String, val script: String, val background: Boolean = false)
    /** Runs [script] every [everyMinutes] while enabled and the app process is alive. */
    data class Schedule(val id: String, val label: String, val script: String, val everyMinutes: Int)
    data class Plugin(val id: String, val title: String, val description: String, val fields: List<Field>, val buttons: List<Button>, val schedules: List<Schedule>, val dir: File)

    private val ID_RE = Regex("^[a-z0-9_-]{1,40}$")
    private val SAFE_SCRIPT = Regex("^[A-Za-z0-9_./-]{1,80}$")

    fun dir(context: Context) = File(AlpineRootfs.rootDir(context), "root/.alpdroid/plugins")

    /** Valid plugins only; a broken manifest is skipped rather than breaking the whole list. */
    fun list(context: Context): List<Plugin> =
        dir(context).listFiles { f -> f.isDirectory && ID_RE.matches(f.name) }?.sortedBy { it.name }
            ?.mapNotNull { runCatching { parse(it) }.getOrNull() } ?: emptyList()

    private fun parse(dir: File): Plugin {
        val j = JSONObject(File(dir, "plugin.json").readText())
        // Caps: an unbounded manifest (100k fields/buttons/schedules from an agent-dropped
        // plugin) would exhaust the scheduler loop and job pool on every tick.
        val fields = (j.optJSONArray("fields") ?: JSONArray()).let { a ->
            (0 until minOf(a.length(), 30)).mapNotNull { i ->
                val f = a.optJSONObject(i) ?: return@mapNotNull null
                val id = f.optString("id")
                val type = f.optString("type", "text")
                if (!ID_RE.matches(id) || type !in setOf("text", "number", "toggle", "select")) return@mapNotNull null
                Field(id, type, f.optString("label", id).take(80), f.opt("default")?.toString()?.take(500) ?: "", (f.optJSONArray("options") ?: JSONArray()).let { o -> (0 until minOf(o.length(), 30)).map { o.optString(it).take(200) } })
            }
        }
        val buttons = (j.optJSONArray("buttons") ?: JSONArray()).let { a ->
            (0 until minOf(a.length(), 30)).mapNotNull { i ->
                val b = a.optJSONObject(i) ?: return@mapNotNull null
                val script = b.optString("script")
                if (!ID_RE.matches(b.optString("id")) || !SAFE_SCRIPT.matches(script) || script.contains("..") || script.startsWith("/") || script.startsWith("logs/")) return@mapNotNull null
                Button(b.optString("id"), b.optString("label", b.optString("id")).take(80), script, b.optBoolean("background", false))
            }
        }
        val schedules = (j.optJSONArray("schedules") ?: JSONArray()).let { a ->
            (0 until minOf(a.length(), 20)).mapNotNull { i ->
                val sc = a.optJSONObject(i) ?: return@mapNotNull null
                val script = sc.optString("script")
                if (!ID_RE.matches(sc.optString("id")) || !SAFE_SCRIPT.matches(script) || script.contains("..") || script.startsWith("/") || script.startsWith("logs/")) return@mapNotNull null
                Schedule(sc.optString("id"), sc.optString("label", sc.optString("id")).take(80), script, sc.optInt("everyMinutes", 60).coerceIn(1, 10080))
            }
        }
        return Plugin(dir.name, j.optString("title", dir.name).take(120), j.optString("description", "").take(1000), fields, buttons, schedules, dir)
    }

    // --- saved field values -------------------------------------------------------------------

    private fun stateFile(p: Plugin) = File(p.dir, "state.json")

    fun loadState(p: Plugin): Map<String, String> {
        val j = runCatching { JSONObject(stateFile(p).readText()) }.getOrNull() ?: JSONObject()
        return p.fields.associate { it.id to (if (j.has(it.id)) j.optString(it.id) else it.default) }
    }

    fun saveValue(p: Plugin, fieldId: String, value: String) {
        val j = runCatching { JSONObject(stateFile(p).readText()) }.getOrNull() ?: JSONObject()
        j.put(fieldId, value)
        runCatching { stateFile(p).writeText(j.toString()) }
    }

    // --- job switches (scheduled scripts / keep-running scripts), stored beside the field values ---

    fun isEnabled(p: Plugin, jobId: String): Boolean =
        runCatching { JSONObject(stateFile(p).readText()).optString("__on_$jobId") == "1" }.getOrDefault(false)

    fun setEnabled(p: Plugin, jobId: String, on: Boolean) = saveValue(p, "__on_$jobId", if (on) "1" else "0")

    // --- approval: an agent (or anything else) can drop a plugin in; it needs the user's OK -----

    private fun prefs(c: Context) = c.getSharedPreferences("alpineterm_plugins", Context.MODE_PRIVATE)

    /** Hash of everything that would run (manifest + scripts, not the saved state). */
    fun fingerprint(p: Plugin): String = fingerprintCached(p)

    // isApproved() → fingerprint() runs on every scheduler tick per job and on several UI
    // paths — a full directory walk + read + SHA-256 each time. Cached per plugin on the
    // newest mtime under its dir: content changes bump mtime, so a hit means nothing to
    // re-hash. state.json/logs are excluded from both the hash and the mtime check.
    private val fpCache = mutableMapOf<String, Pair<Long, String>>()

    /** Streams a file into the digest in 32KB chunks — never holds the whole file in memory,
     *  so a huge file dropped into a plugin dir can't OOM the approval hash walk. */
    private fun hashFile(md: MessageDigest, f: File) {
        runCatching {
            f.inputStream().buffered().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    md.update(buf, 0, n)
                }
            }
        }
    }

    @Synchronized
    private fun fingerprintCached(p: Plugin): String {
        var mtime = 0L
        p.dir.walkTopDown().onEnter { !(it.parentFile == p.dir && it.name == "logs") }.forEach {
            if (it.isFile && it.name != "state.json") mtime = maxOf(mtime, it.lastModified())
        }
        fpCache[p.id]?.let { (cachedMtime, hash) ->
            if (cachedMtime == mtime && hash.isNotEmpty()) return hash
        }
        val md = MessageDigest.getInstance("SHA-256")
        p.dir.walkTopDown().onEnter { !(it.parentFile == p.dir && it.name == "logs") }.filter { it.isFile && it.name != "state.json" }.sortedBy { it.path }.forEach {
            md.update(it.relativeTo(p.dir).path.toByteArray()); hashFile(md, it)
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (fpCache.size > 64) fpCache.clear()
        fpCache[p.id] = mtime to hash
        return hash
    }

    fun isApproved(c: Context, p: Plugin) = prefs(c).getString("approved_${p.id}", null) == fingerprint(p)

    /** Approval against a freshly computed hash — the exec-time gate. The mtime cache in
     *  [fingerprintCached] is only a fast path for display/scheduler checks. */
    fun isApprovedFresh(c: Context, p: Plugin): Boolean {
        val ok = prefs(c).getString("approved_${p.id}", null) == fingerprintUncached(p)
        if (!ok) prefs(c).edit().remove("approved_${p.id}").apply()
        return ok
    }

    private fun fingerprintUncached(p: Plugin): String {
        val md = MessageDigest.getInstance("SHA-256")
        var mtime = 0L
        p.dir.walkTopDown().onEnter { !(it.parentFile == p.dir && it.name == "logs") }.filter { it.isFile && it.name != "state.json" }.sortedBy { it.path }.forEach {
            mtime = maxOf(mtime, it.lastModified())
            md.update(it.relativeTo(p.dir).path.toByteArray()); hashFile(md, it)
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (fpCache.size > 64) fpCache.clear()
        fpCache[p.id] = mtime to hash
        return hash
    }
    fun approve(c: Context, p: Plugin) = prefs(c).edit().putString("approved_${p.id}", fingerprint(p)).apply()

    /** Everything the user is being asked to allow: the manifest, every script a button OR a schedule
     *  runs, and any other file in the plugin (a script can source or call them). Capped only at a size
     *  no legitimate plugin reaches, and says so when it is. */
    fun reviewText(p: Plugin): String {
        val cap = 30_000
        val files = p.dir.walkTopDown().onEnter { !(it.parentFile == p.dir && it.name == "logs") }
            .filter { it.isFile && it.name != "state.json" }.sortedBy { it.path }.toList()
        val sb = StringBuilder()
        var shown = 0
        for (f in files) {
            // Length check before reading: a huge file must not be pulled into memory just to
            // display a capped preview of it.
            if (f.length() > 1_000_000) {
                sb.append("── ${f.relativeTo(p.dir).path} ──\n(over 1MB — inspect the folder before allowing)\n\n")
                continue
            }
            val text = runCatching { f.readText() }.getOrDefault("(unreadable)")
            sb.append("── ${f.relativeTo(p.dir).path} ──\n")
            val room = cap - shown
            if (room <= 0) { sb.append("(not shown: over the size limit — inspect the folder before allowing)\n\n"); continue }
            sb.append(text.take(room)).append('\n')
            if (text.length > room) sb.append("(cut off — ${text.length - room} more characters)\n")
            sb.append('\n')
            shown += minOf(text.length, room)
        }
        return sb.toString().trim()
    }

    fun delete(p: Plugin): Boolean = p.dir.deleteRecursively()

    fun createSample(context: Context): Boolean = runCatching {
        val d = File(dir(context), "hello").apply { mkdirs() }
        File(d, "plugin.json").writeText(
            """{
  "title": "Hello plugin",
  "description": "A template: fields feed the script as FIELD_<ID> environment variables.",
  "fields": [
    {"id": "name", "type": "text", "label": "Name", "default": "world"},
    {"id": "count", "type": "number", "label": "Repeat", "default": 2},
    {"id": "loud", "type": "toggle", "label": "Shout", "default": false},
    {"id": "mood", "type": "select", "label": "Mood", "options": ["happy", "calm", "curious"], "default": "happy"}
  ],
  "buttons": [
    {"id": "greet", "label": "Greet", "script": "hello.sh"},
    {"id": "sysinfo", "label": "System info", "script": "sysinfo.sh"},
    {"id": "watch", "label": "Heartbeat watcher", "script": "watch.sh", "background": true}
  ],
  "schedules": [
    {"id": "ping", "label": "Log the time", "script": "tick.sh", "everyMinutes": 5}
  ]
}
""",
        )
        File(d, "hello.sh").writeText(
            """#!/bin/sh
i=0
while [ "${'$'}i" -lt "${'$'}{FIELD_COUNT:-1}" ]; do
  msg="Hello, ${'$'}FIELD_NAME! (feeling ${'$'}FIELD_MOOD)"
  [ "${'$'}FIELD_LOUD" = 1 ] && msg=${'$'}(echo "${'$'}msg" | tr 'a-z' 'A-Z')
  echo "${'$'}msg"
  i=${'$'}((i+1))
done
command -v alpctl >/dev/null 2>&1 && alpctl toast "Hello from a plugin" >/dev/null 2>&1
exit 0
""",
        )
        File(d, "sysinfo.sh").writeText("#!/bin/sh\nuname -a\ndf -h / | tail -1\ndate\n")
        File(d, "tick.sh").writeText("#!/bin/sh\necho \"tick \$(date)\"\n")
        File(d, "watch.sh").writeText("#!/bin/sh\nwhile true; do echo \"alive \$(date +%T)\"; sleep 60; done\n")
        true
    }.getOrDefault(false)

    /** Runs [script] of [p] in the guest with the field values as FIELD_<ID> env vars. */
    fun run(context: Context, p: Plugin, b: Button, values: Map<String, String>): PtySession? =
        runScript(context, p, b.script, b.id, values)

    fun runScript(context: Context, p: Plugin, script: String, buttonId: String, values: Map<String, String>): PtySession? {
        // Enforced here, not just at the UI call sites: an agent (or anything else) can drop
        // a plugin in, and a future caller that forgets the isApproved() check must fail
        // closed rather than run unreviewed code. Re-hashed fresh (not the mtime cache):
        // the guest can modify an approved script and spoof its mtime back (`touch -r`),
        // which would otherwise keep a stale approval alive for unattended execution.
        if (!isApprovedFresh(context, p)) return null
        val env = HashMap<String, String>()
        env["PLUGIN_ID"] = p.id
        env["PLUGIN_DIR"] = "/root/.alpdroid/plugins/${p.id}"
        p.fields.forEach { f -> env["FIELD_" + f.id.uppercase().replace('-', '_')] = values[f.id] ?: f.default }
        env["BUTTON"] = buttonId
        val cmd = "cd '/root/.alpdroid/plugins/${p.id}' && sh './$script'"
        return AlpineSession.startScript(context, cmd, env)
    }
}
