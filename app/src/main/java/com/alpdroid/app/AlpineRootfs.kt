package com.alpdroid.app

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads and extracts Alpine Linux's official "minirootfs" tarball directly onto disk —
 * no bundled asset, no build-time fetch step. Alpine's CDN always keeps a `latest-stable`
 * alias, so this never needs a hardcoded version to bump: it reads that branch's
 * `latest-releases.yaml` at runtime to discover the current minirootfs filename and checksum,
 * then downloads and verifies exactly that.
 *
 * Downloading straight to app-private storage (rather than shipping the rootfs as an APK
 * asset) also sidesteps a real packaging problem: APK assets can't contain symlinks, and an
 * Alpine rootfs is mostly symlinks (busybox applets). Writing extracted files straight to a
 * real filesystem has no such restriction.
 */
object AlpineRootfs {
    private const val TAG = "AlpDroid/Rootfs"
    private const val DIR_NAME = "alpine-rootfs"
    // Pinned to v3.22 rather than "latest-stable" (currently v3.24): v3.23 introduced a rewritten
    // apk-tools 3.x that replaced the long-established 2.14.x branch, and every network fetch
    // apk-tools 3.x makes — GET *or* POST, any repository, any mirror, over HTTP or HTTPS, any
    // IP family — fails identically with a generic permission error while wget and a browser both
    // reach the exact same URLs fine. That pattern (100% consistent regardless of destination, a
    // real syscall-level failure, no such issue in a much older/simpler client) matches a newer
    // Linux I/O mechanism (io_uring, which apk-tools' rewrite plausibly adopted) being blocked at
    // the Android kernel/seccomp level — a well-documented, real Android security practice given
    // io_uring's history of privilege-escalation CVEs — that no app-level fix can work around.
    // v3.22 is the last release still shipping the proven, years-battle-tested apk-tools 2.14.x,
    // which has never exhibited this failure mode in any proot/container-style deployment.
    // Worth revisiting once apk-tools 3.x has matured and this class of environment is verified
    // working again.
    private const val BASE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases"

    private val ANDROID_ABI_TO_ALPINE_ARCH = mapOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "armv7",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    /** Human-readable last-failure reason, surfaced in the setup screen. Volatile like
     *  downloadedBytes/totalBytes below — written from the background setup thread and polled by
     *  MainActivity's statusPoller on the main thread every 200ms with no other synchronization
     *  between them, so without this the JMM gives no guarantee the poller ever observes an
     *  update in a timely way (the setup screen could appear stuck on a stale status string, or
     *  never show a failure/"Ready" transition, even after the background thread has moved on). */
    @Volatile var lastFailure: String = ""
        private set

    @Volatile var lastStatus: String = ""
        private set

    /** Bytes of the compressed download consumed so far / total (0 if not yet known) — polled by
     *  the setup screen to drive a determinate progress bar during the download+extract phase
     *  (they happen interleaved, streaming, so "compressed bytes consumed" tracks overall
     *  progress reasonably well without needing a separate extraction-progress metric). */
    @Volatile var downloadedBytes: Long = 0
        private set

    @Volatile var totalBytes: Long = 0
        private set

    /**
     * A handful of independently-hosted mirrors, each on a completely different network/ASU than
     * dl-cdn.alpinelinux.org's own (Fastly-backed) CDN — kernel.org's own infrastructure, picked
     * from Alpine's official mirror list (mirrors.alpinelinux.org) for being high-bandwidth,
     * long-established, and geographically spread (US/EU/Asia-Pacific) rather than a small,
     * possibly under-provisioned volunteer mirror. If dl-cdn itself is what's blocked, slow, or
     * intermittently unreachable on a given network — exactly the class of problem a repeatable
     * "Permission denied"/"DNS: transient error" from apk against it points at — these give apk
     * somewhere else to actually resolve packages from instead of every update just failing.
     */
    // ap.edge.kernel.org deliberately left out: confirmed on a real device to present a
    // certificate that doesn't match its own hostname ("SSL certificate subject doesn't match
    // host") — a genuine misconfiguration on that one mirror itself, not anything on our end, and
    // the other three sources already give solid geographic redundancy without a permanently
    // broken entry cluttering every apk update.
    private val FALLBACK_MIRRORS = listOf(
        "https://mirrors.edge.kernel.org/alpine",
        "https://eu.edge.kernel.org/alpine",
    )

    fun rootDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun markerFile(context: Context): File = File(context.filesDir, "$DIR_NAME.ready")

    /** Fast path: true once a previous run finished a verified extraction. */
    fun isReady(context: Context): Boolean = markerFile(context).isFile && shExists(rootDir(context))

    /** For AlpineBackup.restore(), a second, independent way a rootfs gets (re)written — without
     *  this pair, a restore that throws partway through could leave a half-extracted tree that
     *  isReady() still reports ready (the marker from BEFORE the restore never got touched), and
     *  addTab() would then start a shell on that broken tree instead of the caller's own recovery
     *  path re-downloading a clean install. Cleared before extraction starts, written again only
     *  once it actually finishes successfully — exactly the same shape ensureReady() itself uses. */
    fun clearReadyMarker(context: Context) {
        markerFile(context).delete()
    }

    fun markReady(context: Context, label: String) {
        markerFile(context).writeText(label)
    }

    /** Wipes the current install so the next session start re-downloads from scratch — the only
     *  way an already-set-up installation picks up a change to which Alpine release/apk-tools
     *  version [BASE_URL] points at, short of a full app uninstall/reinstall. */
    fun wipeForReinstall(context: Context) {
        synchronized(setupLock) {
            markerFile(context).delete()
            rootDir(context).deleteRecursivelyNoFollow()
        }
    }

    private fun shExists(root: File): Boolean {
        // NOFOLLOW_LINKS: bin/sh is itself a symlink (-> busybox), which only resolves once
        // proot fakes "/" to be this directory. Checking existence without following it avoids
        // a false negative from Android trying to resolve it against the real filesystem root.
        return java.nio.file.Files.exists(File(root, "bin/sh").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * Blocking — call from a background thread. Returns true once a usable Alpine tree exists
     * on disk (either already there, or freshly downloaded and verified in this call).
     *
     * Serialized: two tabs opening at once (or tab + search + plugin job) used to all see
     * !isReady and concurrently delete/re-extract the same rootDir — interleaved deletes and
     * writes producing a half-built tree. Double-checked inside the lock.
     */
    fun ensureReady(context: Context): Boolean {
        if (isReady(context)) return true
        synchronized(setupLock) {
            if (isReady(context)) return true
            return ensureReadyLocked(context)
        }
    }

    /** Guards ensureReady() against itself (concurrent first-install) and against
     *  wipeForReinstall() running while a setup is in flight. */
    private val setupLock = Any()

    private fun ensureReadyLocked(context: Context): Boolean {
        if (isReady(context)) return true

        // A previous run's numbers (a completed 100% from an earlier tab, or a failed attempt's
        // partial count) would otherwise still be sitting here for the setup screen's poller to
        // read on this run's very first tick — showing "Ready (100%)" for a download that hasn't
        // started, or a reinstall's fresh attempt still displaying the failed attempt's byte count.
        downloadedBytes = 0
        totalBytes = 0
        lastFailure = ""

        val alpineArch = ANDROID_ABI_TO_ALPINE_ARCH[Build.SUPPORTED_ABIS.firstOrNull()]
        if (alpineArch == null) {
            lastFailure = "Unsupported CPU architecture: ${Build.SUPPORTED_ABIS.firstOrNull()}"
            return false
        }

        val root = rootDir(context)
        // Extract into a staging sibling, verify, then swap: the checksum used to be checked
        // AFTER extracting straight into the live rootDir (and skipped entirely when the index
        // had no sha256) — a malicious mirror's tarball was already on disk, runnable via
        // proot, before the mismatch was ever noticed. Nothing ever proots into staging.
        val staging = File(context.filesDir, "$DIR_NAME.downloading")
        staging.deleteRecursivelyNoFollow()
        staging.mkdirs()

        return try {
            lastStatus = "Looking up current Alpine release…"
            val release = fetchMinirootfsRelease(alpineArch)
            lastStatus = "Downloading ${release.file}…"
            downloadAndExtract(release, alpineArch, staging)
            if (!shExists(staging)) {
                throw IllegalStateException("extraction finished but bin/sh is missing")
            }
            // Best-effort — expanding the mirror list is a helpful extra, not something that
            // should throw away an otherwise-successful extraction if it somehow fails.
            runCatching { addFallbackMirrorsTo(staging) }
            root.deleteRecursivelyNoFollow()
            if (!staging.renameTo(root)) throw IllegalStateException("could not swap in downloaded system")
            markerFile(context).writeText(release.file)
            lastStatus = "Ready"
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs setup failed", e)
            lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            staging.deleteRecursivelyNoFollow()
            false
        }
    }

    /**
     * Appends [FALLBACK_MIRRORS] after whatever's already in /etc/apk/repositories (normally just
     * dl-cdn.alpinelinux.org, from the official minirootfs tarball itself) at the same version/
     * branch path each existing line already uses (e.g. "/v3.24/main" or "/edge/community") —
     * apk-tools tries every listed repository and simply gets zero packages from whichever ones
     * fail, so this is purely additive, never a replacement. Idempotent: safe to call again on an
     * already-expanded file (skips any line already present), so both the automatic call after a
     * fresh extraction and the on-demand "Add fallback mirrors" Settings button (for an
     * installation that predates this feature) can't ever duplicate lines.
     */
    fun addFallbackMirrors(context: Context): Boolean = addFallbackMirrorsTo(rootDir(context))

    private fun addFallbackMirrorsTo(root: File): Boolean {
        val repoFile = File(root, "etc/apk/repositories")
        if (!repoFile.isFile) return false
        val existingLines = repoFile.readLines()
        val suffixes = existingLines.mapNotNull { line ->
            Regex("""^https?://[^/]+/alpine(/.+)$""").find(line.trim())?.groupValues?.get(1)
        }.distinct()
        if (suffixes.isEmpty()) return false
        val newLines = existingLines.toMutableList()
        for (mirror in FALLBACK_MIRRORS) {
            for (suffix in suffixes) {
                val line = "$mirror$suffix"
                if (existingLines.none { it.trim() == line }) newLines.add(line)
            }
        }
        if (newLines.size == existingLines.size) return false
        repoFile.writeText(newLines.joinToString("\n") + "\n")
        return true
    }

    private data class Release(val file: String, val sha256: String?)

    /**
     * `latest-releases.yaml` lists every release flavor (netboot/minirootfs/uboot/...) as a
     * flat YAML sequence of small mappings. Rather than pull in a YAML dependency for one file
     * with a known-simple shape, this reads it as plain indented text.
     */
    private fun fetchMinirootfsRelease(alpineArch: String): Release {
        val text = httpGetText("$BASE_URL/$alpineArch/latest-releases.yaml")
        // Each list entry is a lone "-" on its own line (YAML block-sequence style), not "- key: ..."
        // on one line — verified against a live fetch, not assumed from a spec reading.
        val blocks = text.split(Regex("(?m)^-\\s*$")).drop(1)
        for (block in blocks) {
            val fields = block.lines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    val idx = trimmed.indexOf(':')
                    if (idx <= 0) null else trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
                }
                .toMap()
            if (fields["flavor"] == "alpine-minirootfs") {
                val file = fields["file"] ?: continue
                return Release(file = file, sha256 = fields["sha256"])
            }
        }
        throw IllegalStateException("no alpine-minirootfs entry in latest-releases.yaml for $alpineArch")
    }

    private fun downloadAndExtract(release: Release, alpineArch: String, root: File) {
        // Required, not optional: without a pinned 64-hex sha256 the download would be trusted
        // blindly — and extraction now happens before any check could run, so this is the gate.
        val expected = release.sha256?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw IllegalStateException("release index has no valid sha256 for ${release.file}")
        val url = "$BASE_URL/$alpineArch/${release.file}"
        val digest = MessageDigest.getInstance("SHA-256")
        downloadedBytes = 0
        totalBytes = 0
        val connection = openHttpConnection(url)
        totalBytes = connection.contentLengthLong.coerceAtLeast(0)
        try {
            connection.inputStream.use { raw ->
                // Buffered + big inflate buffer: GZIPInputStream's 512B default turned a ~3MB
                // minirootfs into hundreds of thousands of tiny reads on first-time setup.
                val buffered = java.io.BufferedInputStream(raw, 64 * 1024)
                val counted = ProgressInputStream(buffered) { n -> downloadedBytes += n }
                val hashed: InputStream = java.security.DigestInputStream(counted, digest)
                GZIPInputStream(hashed, 64 * 1024).use { gz -> extractUstar(gz, root) }
            }
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IllegalStateException("checksum mismatch for ${release.file}")
        }
    }

    /** Wraps a stream to report bytes as they're actually consumed by the reader (the
     *  extractor), not just bytes arrived over the network — an accurate proxy for "how much of
     *  this whole streaming download+extract operation is done" either way. */
    private class ProgressInputStream(private val wrapped: InputStream, private val onRead: (Int) -> Unit) : InputStream() {
        override fun read(): Int {
            val b = wrapped.read()
            if (b >= 0) onRead(1)
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = wrapped.read(b, off, len)
            if (n > 0) onRead(n)
            return n
        }
        override fun close() = wrapped.close()
    }

    private fun httpGetText(url: String): String {
        // Bounded: readBytes() on a network body with no Content-Length check loads whatever
        // a hostile/redirected mirror returns straight into the heap. Only small text files
        // (release indexes) ever flow through here, so a 4MB ceiling is generous, not tight.
        val connection = openHttpConnection(url)
        try {
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > MAX_TEXT_BYTES) throw IllegalStateException("response too large: $url")
                    out.write(buf, 0, n)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    private fun openHttpConnection(url: String): HttpURLConnection {
        // Manual redirect handling: instanceFollowRedirects would follow a compromised mirror
        // to any scheme/host (http://attacker). Only same-scheme https hops, max 5.
        var current = url
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "AlpDroid/1.0")
            val code = connection.responseCode
            if (code in 300..399) {
                val loc = connection.getHeaderField("Location")
                connection.disconnect()
                val next = runCatching { URL(URL(current), loc ?: "").toString() }.getOrNull()
                    ?: throw IllegalStateException("bad redirect from $current")
                if (!next.startsWith("https://")) throw IllegalStateException("refusing non-https redirect: $next")
                current = next
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IllegalStateException("HTTP $code fetching $current")
            }
            return connection
        }
        throw IllegalStateException("too many redirects fetching $url")
    }

    // --- Minimal ustar extractor: directories, regular files, symlinks. Anything else in the
    // archive fails the whole extraction rather than silently producing a half-built rootfs. ---

    private fun extractUstar(input: InputStream, root: File) {
        val rootPath = root.canonicalPath
        val header = ByteArray(512)
        val copyBuf = ByteArray(32 * 1024) // reused across every file entry rather than per-file
        // Bomb caps: the tarball comes over the network and its size fields are untrusted —
        // a malicious mirror response otherwise streams gigabytes to disk before the
        // post-extract sha256 check ever runs.
        var entries = 0
        var totalBytes = 0L
        while (true) {
            readFully(input, header)
            if (header.all { it == 0.toByte() }) break
            val name = header.readString(0, 100).trimEnd('\u0000').removePrefix("./").trimStart('/')
            val mode = header.readString(100, 8).trimEnd('\u0000', ' ').toIntOrNull(8) ?: 0
            val type = header[156].toInt().toChar()
            val linkName = header.readString(157, 100).trimEnd('\u0000')
            val size = header.readString(124, 12).trimEnd('\u0000', ' ').toLongOrNull(8) ?: 0L
            if (++entries > MAX_TAR_ENTRIES) throw IllegalStateException("archive has more than $MAX_TAR_ENTRIES entries")
            totalBytes += size
            if (totalBytes > MAX_TAR_BYTES) throw IllegalStateException("archive extracts more than ${MAX_TAR_BYTES / (1024 * 1024)} MB")

            if (name.isEmpty() || name == ".") {
                // Skip data bytes too: padding-only skip mis-frames every later entry.
                skipSized(input, size, copyBuf)
                skipPadding(input, size)
                continue
            }

            val dest = File(root, name)
            if (!dest.canonicalPath.startsWith("$rootPath/")) {
                throw SecurityException("tar entry escapes rootfs: $name")
            }
            // Symlink-planted parents from earlier entries in this same archive: canonicalPath
            // above resolves through them and can still pass while writing onto the host.
            if (hasSymlinkParent(dest, root)) {
                throw SecurityException("tar entry runs through a symlink: $name")
            }
            when (type) {
                '5' -> {
                    if (java.nio.file.Files.isSymbolicLink(dest.toPath())) throw SecurityException("tar entry is a directory over a symlink: $name")
                    dest.mkdirs()
                    if (java.nio.file.Files.isSymbolicLink(dest.toPath())) throw SecurityException("tar entry is a directory over a symlink: $name")
                    chmodNoFollow(dest, mode.takeIf { it != 0 } ?: 0b111101101) // 755
                }
                '0', '\u0000' -> {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> copySized(input, out, size, copyBuf) }
                    skipPadding(input, size)
                    chmodNoFollow(dest, mode.takeIf { it != 0 } ?: 0b110100100) // 644
                }
                '2' -> {
                    dest.parentFile?.mkdirs()
                    // A symlink whose target escapes the rootfs would resolve onto the host
                    // once proot runs (absolute targets are rebased under root, so only
                    // relative escapes and absolute-outside-root matter — checked lexically).
                    val resolvedTarget = if (linkName.startsWith("/")) File(root, linkName.removePrefix("/")) else File(dest.parentFile, linkName)
                    val resolvedNormalized = resolvedTarget.toPath().normalize()
                    val rootNormalized = root.toPath().normalize()
                    if (resolvedNormalized != rootNormalized && !resolvedNormalized.startsWith(rootNormalized)) {
                        throw SecurityException("tar symlink target escapes rootfs: $name -> $linkName")
                    }
                    dest.delete()
                    Os.symlink(linkName, dest.absolutePath)
                }
                else -> throw IllegalStateException("unsupported tar entry type '$type': $name")
            }
        }
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode and 0b1111111111) }
    }

    /** chmod that never follows symlinks (Os.chmod does) — a planted link would otherwise
     *  chmod its host target. */
    private fun chmodNoFollow(file: File, mode: Int) {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return
        chmod(file, mode)
    }

    /** True when any component of dest's parent chain below root is a symlink (NOFOLLOW).
     *  Staging is freshly created and single-threaded here, so a direct walk is sound. */
    private fun hasSymlinkParent(dest: File, root: File): Boolean {
        val rootPath = root.absolutePath
        var p = dest.parentFile
        while (p != null && p != root && p.absolutePath.startsWith(rootPath)) {
            if (java.nio.file.Files.isSymbolicLink(p.toPath())) return true
            p = p.parentFile
        }
        return false
    }

    private fun skipSized(input: InputStream, size: Long, buf: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n == -1) throw java.io.EOFException("truncated tar entry")
            remaining -= n
        }
    }

    /** Tar-bomb caps for [extractUstar] — a minirootfs is ~50MB; 2GB/500k is headroom. */
    private const val MAX_TAR_ENTRIES = 500_000
    private const val MAX_TAR_BYTES = 2L * 1024 * 1024 * 1024

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw java.io.EOFException("truncated tar archive")
            off += n
        }
    }

    private fun copySized(input: InputStream, out: java.io.OutputStream, size: Long, buf: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n == -1) throw java.io.EOFException("truncated tar entry")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        var remaining = ((512 - (size % 512)) % 512).toInt()
        val buf = ByteArray(512)
        while (remaining > 0) {
            val n = input.read(buf, 0, remaining)
            if (n == -1) throw java.io.EOFException("truncated tar padding")
            remaining -= n
        }
    }

    private fun ByteArray.readString(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.US_ASCII)
    }
}
