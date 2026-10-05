package com.alpdroid.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The `.ad` plugin file: ONE self-contained UTF-8 JSON document that bundles a plugin's manifest and
 * its scripts, so a plugin can be shared, imported from the file picker and exported as a single file
 * (a plugin otherwise is a folder: plugin.json plus scripts). Machine-readable schema:
 * docs/alpdroid-plugin.schema.json; human description: docs/USER_GUIDE.md ("Plugin files").
 *
 * ```
 * {
 *   "alpdroid": 1,                       // format version (required)
 *   "id": "hello",                       // [a-z0-9_-]{1,40}: the plugin's folder name (required)
 *   "title": "Hello plugin", "description": "…", "version": "1.0.0", "author": "…",
 *   "fields":    [{"id","type":"text|number|toggle|select","label","default","options"}],
 *   "buttons":   [{"id","label","script","background"}],
 *   "schedules": [{"id","label","script","everyMinutes"}],
 *   "files":     {"hello.sh": "#!/bin/sh\necho hi\n"}   // path -> text, every script above must be here
 * }
 * ```
 * Importing only writes files; nothing runs until the user has reviewed the scripts and tapped Allow.
 * Validation is strict (unlike the lenient folder loader): a file that would half-work is rejected
 * with a message that says what to fix.
 */
object PluginPackage {
    const val FORMAT_VERSION = 1
    const val EXTENSION = "ad"
    const val MAX_FILE_BYTES = 1_500_000
    private const val MAX_FILES = 64
    private const val MAX_ONE_FILE = 256 * 1024
    private const val MAX_TOTAL = 1024 * 1024
    private val ID_RE = Regex("^[a-z0-9_-]{1,40}$")
    private val PATH_RE = Regex("^[A-Za-z0-9_][A-Za-z0-9_./-]{0,79}$")
    private val FIELD_TYPES = setOf("text", "number", "toggle", "select")

    class Parsed(val id: String, val manifest: JSONObject, val files: Map<String, String>)

    class PluginFormatException(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw PluginFormatException(message)

    fun isSafePath(path: String): Boolean =
        PATH_RE.matches(path) && !path.contains("..") && !path.contains("//") && !path.endsWith("/") &&
            path != "plugin.json" && !path.startsWith("state.json") && path != "logs" && !path.startsWith("logs/")

    fun parse(text: String): Parsed {
        if (text.length > MAX_FILE_BYTES) fail("File is too large for a plugin (over ${MAX_FILE_BYTES / 1000} KB)")
        val j = try { JSONObject(text) } catch (e: Exception) { fail("Not a valid .ad file: it isn't JSON (${e.message?.take(80)})") }
        val version = j.opt("alpdroid")
        if (version !is Int || version != FORMAT_VERSION) fail("Unsupported or missing \"alpdroid\" format version (this app reads version $FORMAT_VERSION)")
        val id = j.optString("id")
        if (!ID_RE.matches(id)) fail("\"id\" must be 1-40 characters of a-z, 0-9, _ or -")

        val filesObj = j.optJSONObject("files") ?: JSONObject()
        if (filesObj.length() > MAX_FILES) fail("Too many files (max $MAX_FILES)")
        val files = LinkedHashMap<String, String>()
        var total = 0
        for (name in filesObj.keys()) {
            if (!isSafePath(name)) fail("File name not allowed: \"$name\"")
            val content = filesObj.opt(name) as? String ?: fail("File \"$name\" must be a text string")
            if (content.length > MAX_ONE_FILE) fail("File \"$name\" is too large (max ${MAX_ONE_FILE / 1024} KB)")
            total += content.length
            files[name] = content
        }
        if (total > MAX_TOTAL) fail("Files are too large together (max ${MAX_TOTAL / 1024} KB)")

        val fields = j.optJSONArray("fields") ?: JSONArray()
        if (fields.length() > 30) fail("Too many fields (max 30)")
        val seen = HashSet<String>()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: fail("fields[$i] must be an object")
            val fid = f.optString("id")
            if (!ID_RE.matches(fid)) fail("fields[$i].id must be 1-40 characters of a-z, 0-9, _ or -")
            if (!seen.add("f:$fid")) fail("Duplicate field id \"$fid\"")
            val type = f.optString("type", "text")
            if (type !in FIELD_TYPES) fail("fields[$i].type must be one of ${FIELD_TYPES.joinToString()}")
            if (type == "select" && (f.optJSONArray("options")?.length() ?: 0) == 0) fail("fields[$i] is a select but has no \"options\"")
        }
        checkJobs(j.optJSONArray("buttons") ?: JSONArray(), "buttons", 30, files, seen)
        checkJobs(j.optJSONArray("schedules") ?: JSONArray(), "schedules", 20, files, seen)

        val manifest = JSONObject(j.toString())
        manifest.remove("files")
        manifest.remove("alpdroid")
        manifest.remove("id")
        return Parsed(id, manifest, files)
    }

    private fun checkJobs(arr: JSONArray, what: String, max: Int, files: Map<String, String>, seen: MutableSet<String>) {
        if (arr.length() > max) fail("Too many $what (max $max)")
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: fail("$what[$i] must be an object")
            val bid = b.optString("id")
            if (!ID_RE.matches(bid)) fail("$what[$i].id must be 1-40 characters of a-z, 0-9, _ or -")
            if (!seen.add("$what:$bid")) fail("Duplicate $what id \"$bid\"")
            val script = b.optString("script")
            if (!isSafePath(script)) fail("$what[$i].script is not an allowed file name: \"$script\"")
            if (script !in files) fail("$what[$i].script \"$script\" is missing from \"files\"")
        }
    }

    /** Builds the .ad text for an installed plugin from its manifest and files. */
    fun export(manifestJson: String, files: Map<String, String>): String {
        val out = JSONObject()
        out.put("alpdroid", FORMAT_VERSION)
        val manifest = JSONObject(manifestJson)
        // Stable, readable order: header keys first, then the rest of the manifest, then the files.
        val keys = manifest.keys().asSequence().toList()
        val id = manifest.optString("id")
        if (id.isNotEmpty()) out.put("id", id)
        for (k in keys) if (k != "id" && k != "alpdroid" && k != "files") out.put(k, manifest.get(k))
        val filesObj = JSONObject()
        for ((name, content) in files.toSortedMap()) filesObj.put(name, content)
        out.put("files", filesObj)
        return out.toString(2)
    }
}
