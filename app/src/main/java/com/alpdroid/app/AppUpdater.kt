package com.alpdroid.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub releases: check → dialog → download → system installer.
 * The releases live in a private repo, so the check/download authenticates with the
 * user's own GitHub token when signed in (same one git already uses) and falls back
 * to anonymous (works if the repo is ever made public). After install Android kills
 * this process and the user re-opens the new version — that *is* the restart.
 */
object AppUpdater {
    /** Owner/repo whose releases carry AlpineTerm-*.apk assets. */
    const val RELEASES_REPO = "anamorsyai/alpdroid"

    data class Update(val tag: String, val name: String, val notes: String, val apkUrl: String, val apiUrl: String, val size: Long)

    /** versionName + versionCode of the running APK. */
    fun currentVersion(context: Context): Pair<String, Long> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        (info.versionName ?: "0") to code
    }.getOrDefault("0" to 0L)

    /** "v1.7.8" vs "1.7.7": numeric triple compare, non-numeric tails ignored. */
    fun isNewer(tag: String, currentName: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(currentName)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
            if (d != 0) return d > 0
        }
        return false
    }

    /** Latest release carrying an APK asset, or null. Blocking — call off the main thread. */
    fun checkForUpdate(context: Context): Update? = runCatching {
        val (currentName, _) = currentVersion(context)
        val conn = openApi(context, "https://api.github.com/repos/$RELEASES_REPO/releases?per_page=5")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val releases = org.json.JSONArray(text)
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            // Drafts AND prereleases are skipped: a prerelease with a higher tag would
            // otherwise be offered as a stable update to every auto-checking client.
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!isNewer(tag, currentName)) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk")) continue
                // The API asset URL (not browser_download_url): with Accept: octet-stream +
                // auth it 302s to a signed host. browser_download_url without auth just 404s
                // on a private repo, and sending the bearer token to a redirect host leaks it.
                return Update(tag, r.optString("name", tag), r.optString("body", ""), a.optString("browser_download_url"), a.optString("url"), a.optLong("size"))
            }
        }
        null
    }.getOrNull()

    private fun openApi(context: Context, url: String): HttpURLConnection {
        // Token only when the user allowed agents to use it — same gate as git/alpctl.
        val token = runCatching {
            if (SettingsStore(context).agentGithubToken) GitHubAuth.validToken(context) else null
        }.getOrNull()
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
    }

    /**
     * Downloads [update]'s APK with progress (downloaded, total). Resumes nothing — a fresh
     * temp file each time, renamed into place only on success. Blocking.
     */
    fun download(context: Context, update: Update, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val dest = File(dir, "AlpineTerm-${update.tag}.apk")
        if (dest.isFile && dest.length() == update.size && update.size > 0) return dest
        val tmp = File(dir, "${dest.name}.part")
        val token = runCatching {
            if (SettingsStore(context).agentGithubToken) GitHubAuth.validToken(context) else null
        }.getOrNull()
        // Follow manually: auth goes only to api.github.com (the asset endpoint),
        // never to the signed redirect host it 302s to — sending the bearer token
        // there would leak it.
        var url = update.apiUrl.ifBlank { update.apkUrl }
        var accept = "application/octet-stream"
        var conn: HttpURLConnection? = null
        // Bounded manual loop (not repeat{}): a bare `return@repeat` on success would
        // keep opening connections for the remaining iterations, leaking each one.
        for (_ in 0 until 5) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (url.contains("api.github.com") && !token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", accept)
            }
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location") ?: throw IllegalStateException("redirect without location")
                // Defense in depth: the api endpoint is HTTPS with pinned system trust, but a
                // forged Location could otherwise downgrade the byte fetch to cleartext http.
                if (!url.startsWith("https://")) throw IllegalStateException("insecure redirect")
                accept = "application/octet-stream"
                c.disconnect()
            } else {
                conn = c
                break
            }
        }
        val connection = conn ?: throw IllegalStateException("too many redirects")
        try {
            if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.coerceAtLeast(0)
            connection.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw java.util.concurrent.CancellationException("download cancelled")
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (update.size > 0 && tmp.length() != update.size) {
                tmp.delete()
                throw IllegalStateException("download size mismatch")
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("could not finalize download")
            tmp.delete() // rename succeeded; no-op. Kept explicit so the finally below reads correctly.
            return dest
        } finally {
            connection.disconnect()
            // Cancel/failure used to abandon the .part forever (never resumed, never swept).
            // Success path renamed it away, so delete() there is a harmless no-op.
            if (tmp.exists() && !dest.isFile) runCatching { tmp.delete() }
        }
    }

    /**
     * Downgrade guard: refuses to hand the installer an APK older than (or equal to) the
     * running one — a substituted release asset (higher tag, stale build) otherwise
     * installs over newer code with only the Update tap as gate. Needs the real version,
     * not the tag string, so it is read from the package itself.
     */
    fun apkVersionCode(context: Context, apk: File): Long = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IllegalStateException("not a valid APK")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrElse { throw IllegalStateException("not a valid APK") }

    /** Hands [apk] to the system installer. Returns false when install permission is missing
     *  (user gets sent to Settings to grant it, then taps Update again). */
    fun installApk(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            android.widget.Toast.makeText(activity, "Allow installing apps, then tap Update again", android.widget.Toast.LENGTH_LONG).show()
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return true
    }
}
