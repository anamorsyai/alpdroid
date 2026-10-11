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
    /** Owner/repo whose releases carry AlpDroid-*.apk assets (releases before 1.7.43 used the AlpineTerm- prefix; any .apk asset is accepted). */
    const val RELEASES_REPO = "anamorsyai/alpdroid"

    data class Update(val tag: String, val name: String, val notes: String, val apkUrl: String, val apiUrl: String, val size: Long, val sha256: String = "", val fileName: String = "",
        /** A delta (`*-from-<installed version>.adp`) that turns the installed APK into this one, when the release carries one. */
        val delta: Delta? = null)

    /** A downloadable delta file: see [ApkDelta]. */
    data class Delta(val apiUrl: String, val url: String, val size: Long, val sha256: String, val name: String)

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

    /** The hex SHA-256 from a GitHub asset `digest` field ("sha256:<hex>"), or "" when it is missing or another kind. */
    fun sha256Of(digest: String): String {
        val hex = digest.removePrefix("sha256:").lowercase()
        return if (digest.startsWith("sha256:") && hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) hex else ""
    }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * What a release check found: an [update] to offer, nothing ([update] null, [failed] false — genuinely up
     * to date), or it could not tell ([failed], with [rateLimited] when GitHub said so). Telling "up to date"
     * from "could not check" matters: a failed check must neither claim "No updates found" nor count as a
     * completed check that suppresses the next one.
     */
    class CheckResult(val update: Update?, val failed: Boolean = false, val rateLimited: Boolean = false)

    /** GitHub's unauthenticated API limit (60/h per IP) was hit. */
    class RateLimitedException : java.io.IOException("GitHub API rate limit reached")

    /** Latest AlpDroid release carrying an APK asset and newer than this build. Blocking. */
    fun checkForUpdate(context: Context): CheckResult = check {
        val (currentName, _) = currentVersion(context)
        selectRelease(
            context,
            belongs = { tag -> !BrowserInstall.isBrowserTag(tag) }, // alpbrowser-v* is a different app
            newer = { tag -> isNewer(tag, currentName) },
            installedName = currentName,
        )
    }

    /**
     * The AlpBrowser companion release to offer: the newest `alpbrowser-v*` release that is newer than the
     * installed AlpBrowser — or, when AlpBrowser is not installed at all, simply the newest one (so the same
     * flow installs it the first time). Blocking — call off the main thread.
     */
    fun checkForBrowserUpdate(context: Context): CheckResult = check {
        val installed = BrowserInstall.installedVersion(context)?.first
        selectRelease(
            context,
            belongs = { tag -> BrowserInstall.isBrowserTag(tag) },
            newer = { tag -> installed == null || isNewer(BrowserInstall.versionFromTag(tag), installed) },
            installedName = installed,
        )
    }

    private inline fun check(block: () -> Update?): CheckResult = try {
        CheckResult(block())
    } catch (e: RateLimitedException) {
        CheckResult(null, failed = true, rateLimited = true)
    } catch (e: Exception) {
        CheckResult(null, failed = true)
    }

    /** What one page of releases tells the search to do next. Pure, so it is unit-tested without a network. */
    internal sealed class Pick {
        class Found(val release: org.json.JSONObject, val asset: org.json.JSONObject) : Pick()
        /** Nothing newer on this page (other channel's releases, drafts, or ones that are not newer) — look at the next one. */
        object Continue : Pick()
    }

    /** `v1.7.90` / `alpbrowser-v0.6.10` -> the bare version, for ordering releases of one channel. */
    private fun tagVersion(tag: String): String = tag.substringAfterLast('-').removePrefix("v")

    /**
     * The newest release on this page that belongs to the channel, is [newer] than what is installed and carries an APK.
     * GitHub does not list releases in version order (an `alpbrowser-v0.6.10` can sit below `alpbrowser-v0.6.2`), so the
     * highest version is chosen explicitly instead of trusting the first match or stopping at the first "not newer" one.
     */
    internal fun pickFromPage(releases: org.json.JSONArray, belongs: (String) -> Boolean, newer: (String) -> Boolean): Pick {
        var best: Pick.Found? = null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            // Drafts AND prereleases are skipped: a prerelease with a higher tag would
            // otherwise be offered as a stable update to every auto-checking client.
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!belongs(tag) || !newer(tag)) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    val cur = best
                    if (cur == null || isNewer(tagVersion(tag), tagVersion(cur.release.optString("tag_name")))) best = Pick.Found(r, a)
                    break
                }
            }
        }
        return best ?: Pick.Continue
    }

    /**
     * The newest release of one channel that is newer than what is installed. Both the AlpDroid app and the
     * AlpBrowser companion publish into this one repo, so a channel's newest release can sit many entries deep
     * once the other channel has shipped a lot — this pages (up to [MAX_RELEASE_PAGES]) until it reaches the
     * channel's newest release, and STOPS at the first in-channel release that is not newer. In the common
     * "already up to date" case that is a single request per channel, which keeps the 15-minute auto-check far
     * under GitHub's unauthenticated 60-requests-per-hour limit.
     */
    private fun selectRelease(context: Context, belongs: (String) -> Boolean, newer: (String) -> Boolean, installedName: String?): Update? {
        var best: Pick.Found? = null
        for (page in 1..MAX_RELEASE_PAGES) {
            val conn = openApi(context, "https://api.github.com/repos/$RELEASES_REPO/releases?per_page=$PER_PAGE&page=$page")
            val text = try {
                val code = conn.responseCode
                if (code == 403 || code == 429) throw RateLimitedException()
                if (code != 200) throw java.io.IOException("HTTP $code")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
            val releases = org.json.JSONArray(text)
            if (releases.length() == 0) break // no more pages
            val p = pickFromPage(releases, belongs, newer)
            if (p is Pick.Found) {
                val cur = best
                if (cur == null || isNewer(tagVersion(p.release.optString("tag_name")), tagVersion(cur.release.optString("tag_name")))) best = p
            }
            if (releases.length() < PER_PAGE) break // that was the last page
        }
        val found = best ?: return null
        val r = found.release
        val a = found.asset
        val tag = r.optString("tag_name")
        // The API asset URL (not browser_download_url): with Accept: octet-stream +
        // auth it 302s to a signed host. browser_download_url without auth just 404s
        // on a private repo, and sending the bearer token to a redirect host leaks it.
        return Update(tag, r.optString("name", tag), r.optString("body", ""), a.optString("browser_download_url"), a.optString("url"), a.optLong("size"), sha256Of(a.optString("digest")), a.optString("name"),
            delta = findDelta(r.optJSONArray("assets"), installedName))
    }

    private const val PER_PAGE = 100

    private const val MAX_RELEASE_PAGES = 3

    /**
     * The delta asset made for the installed version: `<apk name>-from-<installed versionName>.adp`. CI attaches one for
     * each of the last few releases, so a phone that is further behind simply gets no delta and downloads the full APK.
     */
    internal fun findDelta(assets: org.json.JSONArray?, installedName: String?): Delta? {
        if (assets == null || installedName.isNullOrBlank()) return null
        val suffix = "-from-${installedName.trim().removePrefix("v")}.adp"
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(suffix) || a.optLong("size") <= 0) continue
            return Delta(a.optString("url"), a.optString("browser_download_url"), a.optLong("size"), sha256Of(a.optString("digest")), name)
        }
        return null
    }

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
        // The asset's own file name (AlpDroid-*.apk or AlpBrowser-*.apk), so an app download and a
        // browser download never collide on one cached file. Sanitised to a bare name just in case.
        val safeName = update.fileName.substringAfterLast('/').ifBlank { "AlpDroid-${update.tag}.apk" }
        val dest = File(dir, safeName)
        // Size alone isn't proof the cached file is intact: also require it to parse as an APK.
        if (dest.isFile && dest.length() == update.size && update.size > 0) {
            if (runCatching { apkVersionCode(context, dest) }.isSuccess && (update.sha256.isEmpty() || sha256Hex(dest) == update.sha256)) return dest
            dest.delete()
        }
        // A delta against the installed APK first (a fraction of the size); any problem with it falls back to the full file.
        update.delta?.let { d -> tryDelta(context, update, d, dest, dir, onProgress, isCancelled)?.let { return it } }

        val tmp = File(dir, "${dest.name}.part")
        try {
            fetchTo(context, update.apiUrl.ifBlank { update.apkUrl }, tmp, onProgress, isCancelled)
            if (update.size > 0 && tmp.length() != update.size) {
                tmp.delete()
                throw IllegalStateException("download size mismatch")
            }
            // GitHub publishes each asset's SHA-256: when it is there the download must match it exactly (a damaged or
            // swapped file is refused before the installer ever sees it; Android's own signature check still applies).
            if (update.sha256.isNotEmpty() && sha256Hex(tmp) != update.sha256) {
                tmp.delete()
                throw IllegalStateException("download checksum mismatch")
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("could not finalize download")
            return dest
        } finally {
            // Cancel/failure used to abandon the .part forever (never resumed, never swept).
            // Success path renamed it away, so delete() there is a harmless no-op.
            if (tmp.exists() && !dest.isFile) runCatching { tmp.delete() }
        }
    }

    /** The installed APK that deltas are applied to, or null when it is not a single plain APK this process can read. */
    internal fun installedBaseApk(context: Context, update: Update): File? = runCatching {
        val info = if (BrowserInstall.isBrowserTag(update.tag)) context.packageManager.getApplicationInfo(BrowserInstall.PACKAGE, 0) else context.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return@runCatching null
        File(info.sourceDir).takeIf { it.isFile && it.canRead() }
    }.getOrNull()

    /**
     * Downloads [delta], applies it to the installed APK and returns the rebuilt, verified APK ([dest]) — or null when
     * anything about that fails (no readable base APK, a different installed build, a damaged download or result), so the
     * caller downloads the whole APK instead. Cancelling is the one thing that is not swallowed.
     */
    private fun tryDelta(context: Context, update: Update, delta: Delta, dest: File, dir: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File? {
        val base = installedBaseApk(context, update) ?: return null
        val patch = File(dir, "${delta.name}.part")
        val rebuilt = File(dir, "${dest.name}.delta")
        try {
            fetchTo(context, delta.apiUrl.ifBlank { delta.url }, patch, onProgress, isCancelled)
            if (delta.size > 0 && patch.length() != delta.size) return null
            if (delta.sha256.isNotEmpty() && sha256Hex(patch) != delta.sha256) return null
            ApkDelta.apply(base, patch, rebuilt, expectedSha256 = update.sha256)
            if (update.size > 0 && rebuilt.length() != update.size) return null
            // The same proof of integrity as a full download: it parses as an APK (the caller then checks the package and version).
            if (runCatching { apkVersionCode(context, rebuilt) }.isFailure) return null
            if (!rebuilt.renameTo(dest)) return null
            return dest
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            patch.delete()
            if (!dest.isFile) rebuilt.delete()
        }
    }

    /**
     * Streams one release asset into [tmp] with progress. Follows redirects by hand: auth goes only to api.github.com (the
     * asset endpoint), never to the signed host it 302s to — sending the bearer token there would leak it.
     */
    private fun fetchTo(context: Context, startUrl: String, tmp: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val token = runCatching {
            if (SettingsStore(context).agentGithubToken) GitHubAuth.validToken(context) else null
        }.getOrNull()
        var url = startUrl
        var accept = "application/octet-stream"
        var conn: HttpURLConnection? = null
        // Bounded manual loop (not repeat{}): a bare `return@repeat` on success would
        // keep opening connections for the remaining iterations, leaking each one.
        for (attempt in 0 until 5) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (runCatching { URL(url).host == "api.github.com" }.getOrDefault(false) && !token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
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
        } finally {
            connection.disconnect()
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

    /**
     * The versionCode of [apk], but ONLY when it really is the app [expectedPackage] — null for anything else
     * (not an APK, or a different app). Without the package check an `AlpBrowser-*.apk` asset that is actually an
     * AlpDroid build (or the reverse) would pass the downgrade guard on its version number alone; Android's
     * installer still enforces the signature, but the user should be refused here, with the right message.
     */
    fun verifiedVersionCode(context: Context, apk: File, expectedPackage: String): Long? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return@runCatching null
        if (info.packageName != expectedPackage) return@runCatching null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull()

    /** Hands [apk] to the system installer. Returns false when install permission is missing
     *  (user gets sent to Settings to grant it, then taps Update again). */
    fun installApk(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            android.widget.Toast.makeText(activity, "Allow installing apps, then return here — the install prompt will reappear", android.widget.Toast.LENGTH_LONG).show()
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
