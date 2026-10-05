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

    fun dir(context: Context) = File(AlpineRootfs.rootDir(context), "root/.alpdroid/plugins")

    /** Valid plugins only; a broken manifest is skipped rather than breaking the whole list. */
    fun list(context: Context): List<Plugin> =
        dir(context).listFiles { f -> f.isDirectory && ID_RE.matches(f.name) }?.sortedBy { it.name }
            ?.mapNotNull { runCatching { parse(it) }.getOrNull() } ?: emptyList()

    private fun parse(dir: File): Plugin {
        // Length gate: an agent/guest-dropped multi-MB plugin.json would otherwise OOM the
        // scheduler tick (every 30s) and every panel build on JSONObject parsing. Throwing
        // keeps the "broken manifest is skipped" contract (caller runCatchings this).
        val manifest = File(dir, "plugin.json").takeIf { it.isFile && it.length() <= 256 * 1024 }?.readText()
            ?: throw IllegalStateException("missing or oversized plugin.json")
        val j = JSONObject(manifest)
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
                if (!ID_RE.matches(b.optString("id")) || !PluginPackage.isSafePath(script)) return@mapNotNull null
                Button(b.optString("id"), b.optString("label", b.optString("id")).take(80), script, b.optBoolean("background", false))
            }
        }
        val schedules = (j.optJSONArray("schedules") ?: JSONArray()).let { a ->
            (0 until minOf(a.length(), 20)).mapNotNull { i ->
                val sc = a.optJSONObject(i) ?: return@mapNotNull null
                val script = sc.optString("script")
                if (!ID_RE.matches(sc.optString("id")) || !PluginPackage.isSafePath(script)) return@mapNotNull null
                Schedule(sc.optString("id"), sc.optString("label", sc.optString("id")).take(80), script, sc.optInt("everyMinutes", 60).coerceIn(1, 10080))
            }
        }
        return Plugin(dir.name, j.optString("title", dir.name).take(120), j.optString("description", "").take(1000), fields, buttons, schedules, dir)
    }

    // --- saved field values -------------------------------------------------------------------

    private fun stateFile(p: Plugin) = File(p.dir, "state.json")

    // Length gate (guest-writable file): a huge state.json must not OOM the scheduler tick.
    private fun readStateJson(p: Plugin): JSONObject {
        val f = stateFile(p)
        if (!f.isFile || f.length() > 256 * 1024) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
    }

    fun loadState(p: Plugin): Map<String, String> {
        val j = readStateJson(p)
        return p.fields.associate { it.id to (if (j.has(it.id)) j.optString(it.id) else it.default) }
    }

    // Synchronized + atomic replace: UI field edits, job switches and the scheduler all
    // read-modify-write this one file — unsynchronized, concurrent saves lost each other's
    // keys, and a kill mid-writeText() left a truncated (then ignored) state.json.
    @Synchronized
    fun saveValue(p: Plugin, fieldId: String, value: String) {
        val j = readStateJson(p)
        j.put(fieldId, value)
        runCatching {
            val f = stateFile(p)
            val tmp = File(f.parentFile, "state.json.tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(f)) { f.writeText(j.toString()); tmp.delete() }
        }
    }

    // --- job switches (scheduled scripts / keep-running scripts), stored beside the field values ---

    fun isEnabled(p: Plugin, jobId: String): Boolean =
        runCatching { readStateJson(p).optString("__on_$jobId") == "1" }.getOrDefault(false)

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

    /** Everything in a plugin folder that can run or be read by a script and therefore has to be reviewed and
     *  hashed: all files except the top-level saved state (state.json, state.json.tmp) and the top-level logs/
     *  folder. Symbolic links are never followed — a link could point at app-private files or loop forever —
     *  and their presence makes the plugin unapprovable ([hasLinks]). */
    private class Payload(val files: List<File>, val hasLinks: Boolean)

    private fun payload(p: Plugin): Payload {
        val files = ArrayList<File>()
        var links = false
        fun walk(dir: File, top: Boolean) {
            val children = dir.listFiles() ?: return
            for (c in children.sortedBy { it.name }) {
                if (top && (c.name == "logs" || c.name == "state.json" || c.name == "state.json.tmp")) continue
                if (java.nio.file.Files.isSymbolicLink(c.toPath())) { links = true; continue }
                if (c.isDirectory) { if (files.size < 2000) walk(c, false) } else if (c.isFile) files += c
            }
        }
        walk(p.dir, true)
        return Payload(files.sortedBy { it.path }, links)
    }

    private const val SYMLINK_FINGERPRINT = "unapprovable:symbolic-link"

    @Synchronized
    private fun fingerprintCached(p: Plugin): String {
        val payload = payload(p)
        if (payload.hasLinks) return SYMLINK_FINGERPRINT
        var mtime = 0L
        payload.files.forEach { mtime = maxOf(mtime, it.lastModified()) }
        fpCache[p.id]?.let { (cachedMtime, hash) ->
            if (cachedMtime == mtime && hash.isNotEmpty()) return hash
        }
        val md = MessageDigest.getInstance("SHA-256")
        payload.files.forEach { md.update(it.relativeTo(p.dir).path.toByteArray()); hashFile(md, it) }
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
        val payload = payload(p)
        if (payload.hasLinks) return SYMLINK_FINGERPRINT
        val md = MessageDigest.getInstance("SHA-256")
        var mtime = 0L
        payload.files.forEach {
            mtime = maxOf(mtime, it.lastModified())
            md.update(it.relativeTo(p.dir).path.toByteArray()); hashFile(md, it)
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        // fpCache is guarded by this object's monitor (fingerprintCached is @Synchronized);
        // this path is called from other threads and used to mutate it unguarded.
        synchronized(this) {
            if (fpCache.size > 64) fpCache.clear()
            fpCache[p.id] = mtime to hash
        }
        return hash
    }

    /** Approves exactly what was reviewed: [expected] is the fingerprint [review] returned when the dialog was
     *  built. If the files changed while the dialog was open (or the plugin holds symbolic links) nothing is
     *  approved and false is returned. */
    fun approve(c: Context, p: Plugin, expected: String): Boolean {
        val fresh = fingerprintUncached(p)
        if (fresh == SYMLINK_FINGERPRINT || fresh != expected) return false
        prefs(c).edit().putString("approved_${p.id}", fresh).apply()
        return true
    }

    /** What the user is asked to allow: [text] (manifest and every file), the [fingerprint] of exactly that
     *  content, and whether it is [complete]. An incomplete review (a file too large or cut off, unreadable, or
     *  symbolic links present) must not be approvable — the user could not have seen it all. */
    class Review(val text: String, val fingerprint: String, val complete: Boolean)

    fun review(p: Plugin): Review {
        val payload = payload(p)
        val fingerprint = fingerprintUncached(p)
        val cap = 30_000
        val sb = StringBuilder()
        var complete = true
        if (payload.hasLinks) {
            complete = false
            sb.append("This plugin contains symbolic links, which are not allowed — it can't be approved.\n\n")
        }
        var shown = 0
        for (f in payload.files) {
            val rel = f.relativeTo(p.dir).path
            // Length check before reading: a huge file must not be pulled into memory just to
            // display a capped preview of it.
            if (f.length() > 1_000_000) {
                complete = false
                sb.append("── $rel ──\n(over 1MB — too large to review, so this plugin can't be approved)\n\n")
                continue
            }
            val text = runCatching { f.readText() }.getOrNull()
            sb.append("── $rel ──\n")
            if (text == null) { complete = false; sb.append("(unreadable)\n\n"); continue }
            val room = cap - shown
            if (room <= 0) { complete = false; sb.append("(not shown: over the size limit)\n\n"); continue }
            sb.append(text.take(room)).append('\n')
            if (text.length > room) { complete = false; sb.append("(cut off — ${text.length - room} more characters)\n") }
            sb.append('\n')
            shown += minOf(text.length, room)
        }
        if (!complete) sb.append("This plugin is too large to review in full, so it can't be approved from here.")
        return Review(sb.toString().trim(), fingerprint, complete)
    }

    /** Removes the plugin and its approval; a plain recursive delete would follow a guest-planted symlink out of
     *  the folder, so it never follows links. */
    fun delete(c: Context, p: Plugin): Boolean {
        prefs(c).edit().remove("approved_${p.id}").apply()
        synchronized(this) { fpCache.remove(p.id) }
        return p.dir.deleteRecursivelyNoFollow()
    }

    fun exists(context: Context, id: String) = File(dir(context), id).isDirectory

    /** Writes a validated .ad package into ~/.alpdroid/plugins/<id>/. An existing plugin of the same id
     *  is replaced, but its saved field values (state.json, with every job switch cleared) and logs are kept.
     *  The new plugin is built in a staging folder and swapped in only after every file was written, so a failure
     *  half-way never destroys the old one. The approval fingerprint covers the new files, so the plugin shows
     *  "needs review" until the user taps Allow — importing never runs anything. */
    fun install(context: Context, parsed: PluginPackage.Parsed): File {
        val pluginsDir = dir(context).apply { mkdirs() }
        val d = File(pluginsDir, parsed.id)
        val staging = File(pluginsDir, "${parsed.id}.new")
        staging.deleteRecursivelyNoFollow()
        staging.mkdirs()
        try {
            val root = staging.canonicalPath + File.separator
            for ((name, content) in parsed.files) {
                val f = File(staging, name)
                // Belt and braces on top of PluginPackage's name checks: never write outside the plugin folder.
                if (!f.canonicalPath.startsWith(root)) throw IllegalArgumentException("unsafe path: $name")
                f.parentFile?.mkdirs()
                f.writeText(content)
                if (name.endsWith(".sh")) f.setExecutable(true, false)
            }
            File(staging, "plugin.json").writeText(parsed.manifest.toString(2))
            // Carry over what is the user's, not the code: saved values (job switches off — a new script must
            // never start unattended on an old "on") and logs.
            val oldState = File(d, "state.json")
            if (oldState.isFile && !java.nio.file.Files.isSymbolicLink(oldState.toPath()) && oldState.length() <= 256 * 1024) {
                val values = runCatching { JSONObject(oldState.readText()) }.getOrNull()
                if (values != null) {
                    values.keys().asSequence().toList().filter { it.startsWith("__on_") }.forEach { values.remove(it) }
                    File(staging, "state.json").writeText(values.toString())
                }
            }
            val oldLogs = File(d, "logs")
            if (oldLogs.isDirectory && !java.nio.file.Files.isSymbolicLink(oldLogs.toPath())) oldLogs.renameTo(File(staging, "logs"))
            d.deleteRecursivelyNoFollow()
            if (!staging.renameTo(d)) throw IllegalStateException("couldn't move the new plugin into place")
        } catch (e: Exception) {
            staging.deleteRecursivelyNoFollow()
            throw e
        }
        return d
    }

    /** The plugin as a single .ad document (manifest + every text file it contains). Saved field values
     *  and logs are not included. Throws if the plugin holds something that can't be bundled. */
    fun exportText(p: Plugin): String {
        val manifest = JSONObject(File(p.dir, "plugin.json").readText()).put("id", p.id)
        val files = LinkedHashMap<String, String>()
        val payload = payload(p)
        if (payload.hasLinks) throw IllegalStateException("the plugin contains symbolic links")
        payload.files.filter { it.relativeTo(p.dir).path != "plugin.json" }.forEach {
            val rel = it.relativeTo(p.dir).path
            if (!PluginPackage.isSafePath(rel)) throw IllegalStateException("can't bundle \"$rel\"")
            if (it.length() > 256 * 1024) throw IllegalStateException("\"$rel\" is too large to bundle")
            files[rel] = it.readText()
        }
        return PluginPackage.export(manifest.toString(), files)
    }

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
