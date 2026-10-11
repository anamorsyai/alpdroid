package com.alpdroid.browser

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Extensions for AlpBrowser. A WebView cannot load Chrome (.crx) or Firefox (.xpi) extension packages, so extensions
 * here are userscripts — the same `.user.js` format Tampermonkey/Violentmonkey use, which is what most browser
 * "extensions" people actually install are. They are stored on disk and stay installed (and enabled) across restarts
 * until the user removes them; nothing is verified, signed or time-limited.
 */
data class UserScript(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val excludes: List<String>,
    /** document-start, document-end or document-idle. */
    val runAt: String,
    val noFrames: Boolean,
    val enabled: Boolean,
    val sourceUrl: String?,
    val code: String,
)

object UserScriptParser {
    class Meta(
        val name: String, val namespace: String, val version: String, val description: String,
        val matches: List<String>, val excludes: List<String>, val runAt: String, val noFrames: Boolean,
    )

    private val block = Regex("//\\s*==UserScript==(.*?)//\\s*==/UserScript==", RegexOption.DOT_MATCHES_ALL)
    private val line = Regex("^\\s*//\\s*@([\\w:-]+)(?:\\s+(.*?))?\\s*$")

    /** Metadata from the `==UserScript==` header, or null when the text has no header (not a userscript). */
    fun parse(text: String): Meta? {
        val body = block.find(text)?.groupValues?.get(1) ?: return null
        val m = HashMap<String, MutableList<String>>()
        body.lineSequence().forEach { l ->
            val r = line.find(l) ?: return@forEach
            m.getOrPut(r.groupValues[1].lowercase()) { mutableListOf() }.add(r.groupValues[2])
        }
        fun one(k: String) = m[k]?.firstOrNull()?.trim().orEmpty()
        val runAt = one("run-at").lowercase().ifEmpty { "document-idle" }.let {
            if (it in setOf("document-start", "document-end", "document-idle")) it else "document-idle"
        }
        return Meta(
            name = one("name").ifEmpty { "Unnamed script" },
            namespace = one("namespace"),
            version = one("version"),
            description = one("description"),
            matches = (m["match"].orEmpty() + m["include"].orEmpty()).map { it.trim() }.filter { it.isNotEmpty() },
            excludes = (m["exclude"].orEmpty() + m["exclude-match"].orEmpty()).map { it.trim() }.filter { it.isNotEmpty() },
            runAt = runAt,
            noFrames = m.containsKey("noframes"),
        )
    }
}

/** URL matching for @match / @include / @exclude. */
object UrlPatterns {
    /** Regex source (for both Kotlin and JavaScript) equivalent to [pattern], or null when it cannot be understood. */
    fun toRegex(pattern: String): String? {
        val p = pattern.trim()
        if (p.isEmpty()) return null
        if (p == "<all_urls>" || p == "*") return "^https?://.*$"
        // /regex/ form (an @include allows it).
        if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) {
            val inner = p.substring(1, p.length - 1)
            return if (runCatching { Regex(inner) }.isSuccess) inner else null
        }
        val schemeEnd = p.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = p.substring(0, schemeEnd)
            val rest = p.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            val host = if (slash == -1) rest else rest.substring(0, slash)
            val path = if (slash == -1) "/*" else rest.substring(slash)
            val schemeRe = if (scheme == "*") "https?" else globPart(scheme)
            // "*.example.com" also covers example.com itself, as in Chrome match patterns.
            val hostRe = if (host.startsWith("*.")) "(?:[^/]+\\.)?" + globPart(host.substring(2)) else globPart(host)
            return "^$schemeRe://$hostRe${globPart(path)}$"
        }
        return "^" + globPart(p) + "$"
    }

    private fun globPart(g: String): String = buildString {
        // Plain backslash escapes, not Regex.escape's \Q..\E: the same source also runs in the page's JavaScript.
        g.forEach { c ->
            when {
                c == '*' -> append(".*")
                c in "\\^$.|?+()[]{}/" -> { append('\\'); append(c) }
                else -> append(c)
            }
        }
    }

    fun matches(patterns: List<String>, excludes: List<String>, url: String): Boolean {
        fun hit(list: List<String>) = list.any { p -> toRegex(p)?.let { runCatching { Regex(it).containsMatchIn(url) }.getOrDefault(false) } == true }
        // No @match/@include means "every web page", the friendlier reading for a script the user installed on purpose.
        val included = if (patterns.isEmpty()) url.startsWith("http://") || url.startsWith("https://") else hit(patterns)
        return included && !hit(excludes)
    }
}

/**
 * On-disk store: one `<id>.user.js` per script plus a `<id>.json` holding its state (enabled, where it came from).
 * Files live in the app's private storage, which survives restarts and updates.
 */
class ExtensionStore(private val dir: File) {
    sealed interface InstallResult {
        data class Installed(val script: UserScript, val replaced: Boolean) : InstallResult
        data class Rejected(val reason: String) : InstallResult
    }

    init { dir.mkdirs() }

    @Synchronized fun list(): List<UserScript> =
        (dir.listFiles { f -> f.name.endsWith(".user.js") } ?: emptyArray()).mapNotNull { load(it) }.sortedBy { it.name.lowercase() }

    @Synchronized fun get(id: String): UserScript? = file(id)?.let { load(it) }

    @Synchronized fun install(text: String, sourceUrl: String? = null): InstallResult {
        val trimmed = text.removePrefix("﻿")
        if (trimmed.startsWith("Cr24") || trimmed.startsWith("PK")) {
            return InstallResult.Rejected("Chrome (.crx) and Firefox (.xpi) packages cannot run inside Android's WebView. Install the userscript (.user.js) version of the extension instead.")
        }
        if (trimmed.length > MAX_SCRIPT_BYTES) return InstallResult.Rejected("Script is larger than ${MAX_SCRIPT_BYTES / 1024} KiB")
        val meta = UserScriptParser.parse(trimmed)
            ?: return InstallResult.Rejected("Not a userscript: no // ==UserScript== header found")
        val id = idFor(meta.name, meta.namespace)
        val replaced = file(id) != null
        val keepEnabled = if (replaced) get(id)?.enabled ?: true else true
        File(dir, "$id.user.js").writeText(trimmed)
        writeState(id, keepEnabled, sourceUrl ?: get(id)?.sourceUrl)
        return InstallResult.Installed(get(id)!!, replaced)
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean): Boolean {
        val s = get(id) ?: return false
        writeState(id, enabled, s.sourceUrl)
        return true
    }

    @Synchronized fun remove(id: String): Boolean {
        val f = file(id) ?: return false
        File(dir, "$id.json").delete()
        return f.delete()
    }

    private fun file(id: String): File? =
        if (id.matches(Regex("[A-Za-z0-9_-]+"))) File(dir, "$id.user.js").takeIf { it.isFile } else null

    private fun load(f: File): UserScript? = runCatching {
        val id = f.name.removeSuffix(".user.js")
        val code = f.readText()
        val meta = UserScriptParser.parse(code) ?: return null
        val state = File(dir, "$id.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        UserScript(
            id, meta.name, meta.version, meta.description, meta.matches, meta.excludes, meta.runAt, meta.noFrames,
            enabled = state?.optBoolean("enabled", true) ?: true,
            sourceUrl = state?.optString("sourceUrl")?.takeIf { it.isNotEmpty() },
            code = code,
        )
    }.getOrNull()

    private fun writeState(id: String, enabled: Boolean, sourceUrl: String?) {
        File(dir, "$id.json").writeText(JSONObject().put("enabled", enabled).put("sourceUrl", sourceUrl ?: "").toString())
    }

    private fun idFor(name: String, namespace: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "script" }
        val hash = MessageDigest.getInstance("SHA-256").digest("$namespace\n$name".toByteArray())
            .take(3).joinToString("") { "%02x".format(it) }
        return "$slug-$hash"
    }

    companion object { const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024 }
}

/** Builds the JavaScript that actually runs one userscript inside a page. */
object UserScriptRunner {
    private fun js(s: String): String = JSONObject.quote(s)

    fun wrap(s: UserScript): String {
        val matchRegexes = if (s.matches.isEmpty()) listOf("^https?://.*$") else s.matches.mapNotNull { UrlPatterns.toRegex(it) }
        val excludeRegexes = s.excludes.mapNotNull { UrlPatterns.toRegex(it) }
        fun arr(l: List<String>) = l.joinToString(",", "[", "]") { js(it) }
        val key = "__alpext_${s.id}_"
        return """
(function(){
try{
if(${s.noFrames}&&window.top!==window)return;
var inc=${arr(matchRegexes)},exc=${arr(excludeRegexes)},u=location.href;
function hit(l){for(var i=0;i<l.length;i++){try{if(new RegExp(l[i]).test(u))return true}catch(e){}}return false}
if(!hit(inc)||hit(exc))return;
var P=${js(key)};
var GM_info={script:{name:${js(s.name)},version:${js(s.version)},description:${js(s.description)}},scriptHandler:'AlpBrowser'};
function GM_log(){console.log.apply(console,arguments)}
function GM_addStyle(css){var e=document.createElement('style');e.textContent=css;(document.head||document.documentElement).appendChild(e);return e}
function GM_setValue(k,v){try{localStorage.setItem(P+k,JSON.stringify(v))}catch(e){}}
function GM_getValue(k,d){try{var v=localStorage.getItem(P+k);return v===null?d:JSON.parse(v)}catch(e){return d}}
function GM_deleteValue(k){try{localStorage.removeItem(P+k)}catch(e){}}
function GM_registerMenuCommand(){}
function GM_openInTab(url){window.open(url,'_blank')}
function GM_xmlhttpRequest(o){fetch(o.url,{method:o.method||'GET',headers:o.headers,body:o.data}).then(function(r){return r.text().then(function(t){o.onload&&o.onload({status:r.status,statusText:r.statusText,responseText:t,finalUrl:r.url})})}).catch(function(e){o.onerror&&o.onerror(e)})}
var unsafeWindow=window;
function run(){try{
(function(){
${s.code}
}).call(window);
}catch(e){console.error('[AlpBrowser extension '+${js(s.name)}+']',e)}}
var at=${js(s.runAt)};
if(at==='document-start'||document.readyState==='complete')run();
else if(at==='document-end'){if(document.readyState==='interactive')run();else document.addEventListener('DOMContentLoaded',run)}
else{if(document.readyState==='interactive'||document.readyState==='complete')run();else window.addEventListener('load',run)}
}catch(e){console.error('[AlpBrowser extension]',e)}
})();
""".trimIndent()
    }
}
