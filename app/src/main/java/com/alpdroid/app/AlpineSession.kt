package com.alpdroid.app

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Builds and starts the actual guest shell: Alpine under `proot` when everything needed for
 * that is in place, otherwise a plain `/system/bin/sh` — both run through [PtySession], so even
 * the fallback gets a real terminal (job control, raw mode, resize) rather than a bare pipe.
 *
 * Two things make Alpine's guest able to reach the outside world exactly like the host does:
 * - **Network**: nothing to set up. `proot` never creates a network namespace, so guest
 *   processes share the device's live network stack — same interfaces, same IP, same
 *   reachability — automatically. The only gap is DNS (no netd running inside the guest to
 *   answer its resolver), fixed by writing the host's own DNS servers into resolv.conf below.
 * - **Storage**: `-b <shared storage>:/sdcard` bind-mounts the device's real shared storage
 *   straight into the guest at the same path Termux uses, so a file manager or another app
 *   sees exactly what the guest writes there, live, and vice versa.
 */
object AlpineSession {
    private const val TAG = "AlpDroid/Session"

    /** Guards writeResolvConf()/writeApkHostsIPv4Only() below — both read-modify-write files
     *  shared by every session in the same rootfs (etc/resolv.conf, etc/hosts), and since package
     *  search moved onto its own dedicated executor (searchExecutor, separate from the
     *  backgroundExecutor every tab start runs on), those two call sites can now genuinely run
     *  concurrently: opening a new tab while a search is in flight used to be serialized for free
     *  by sharing one executor thread, and no longer is. */
    private val networkConfigLock = Any()

    data class StartResult(val session: PtySession, val backendLabel: String)

    /**
     * [sessionId] must be unique per concurrently-running session (multi-tab support): each
     * `proot` process needs its own scratch dir for its loader/glue-rootfs bookkeeping — two
     * proot instances racing to write the same one is exactly the kind of thing that produces
     * hard-to-reproduce corruption. The guest's actual `/tmp` is a separate, shared bind mount
     * (see [startAlpine]) so tabs still see one common `/tmp` the way real Alpine sessions do.
     */
    fun start(context: Context, rows: Int, cols: Int, sessionId: Int): StartResult {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val bridge = File(nativeLibDir, "libpty_bridge.so")
        if (!bridge.isFile) {
            throw IllegalStateException("pty_bridge helper missing from $nativeLibDir — broken build")
        }

        val proot = File(nativeLibDir, "libalpineterm_proot.so")
        val alpineReady = proot.isFile && AlpineRootfs.ensureReady(context)

        return if (alpineReady) {
            try {
                StartResult(startAlpine(context, bridge, proot, nativeLibDir, rows, cols, sessionId), "Alpine Linux (proot)")
            } catch (e: Exception) {
                Log.w(TAG, "Alpine start failed, falling back to system shell", e)
                StartResult(startSystemShell(context, bridge, rows, cols), "system shell (Alpine failed: ${e.message})")
            }
        } else {
            val reason = if (!proot.isFile) "proot not bundled for this device's CPU" else AlpineRootfs.lastFailure
            StartResult(startSystemShell(context, bridge, rows, cols), "system shell ($reason)")
        }
    }

    private fun startAlpine(context: Context, bridge: File, proot: File, nativeLibDir: File, rows: Int, cols: Int, sessionId: Int): PtySession {
        val root = AlpineRootfs.rootDir(context)
        writeResolvConf(context, root)
        writeApkHostsIPv4Only(root)
        writeShellProfile(root)

        // Shared across tabs (one common guest /tmp, like real Alpine sessions), unlike the
        // per-session proot scratch dir below.
        val guestTmp = File(context.cacheDir, "guest-tmp").apply { mkdirs() }
        val prootScratch = File(context.cacheDir, "proot-scratch-$sessionId").apply { mkdirs() }
        val storageRoot = StorageAccess.sharedStorageRoot()

        val argv = mutableListOf(
            proot.absolutePath,
            "-r", root.absolutePath,
            "-0", // emulate uid 0 inside the guest: apk and friends expect root
            // Reports an older kernel release to the guest than the device's real one. musl/
            // LibreSSL's own feature detection uses uname() to decide whether to even attempt
            // getrandom() (added expecting kernel >= 3.17) before every TLS handshake — on a
            // device where that syscall is blocked or mistranslated under proot's ptrace-based
            // emulation (some OEM kernels harden exactly this one beyond stock AOSP), OpenSSL/
            // LibreSSL treats the resulting error as fatal rather than falling back to reading
            // /dev/urandom directly, which is otherwise a completely ordinary, working file read
            // through the /dev bind mount above. Reporting 3.10 here skips the syscall attempt
            // entirely, going straight to the fallback that already works. This is the standard,
            // low-risk proot-distro community workaround for exactly this failure mode: every
            // TLS connection failing identically regardless of destination host or network.
            "-k", "3.10",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${guestTmp.absolutePath}:/tmp",
        )
        if (StorageAccess.isGranted(context) && storageRoot.isDirectory) {
            argv += listOf("-b", "${storageRoot.absolutePath}:/sdcard")
        }
        argv += removableDriveBinds(context, root)
        // Not bare "/bin/sh": some CLI tools spawn a detached background daemon that doesn't
        // close/redirect the terminal file descriptors it inherits when it forks (opencode's own
        // "Starting background server..." is one example) — left running, it can keep this pty's
        // slave side open indefinitely, which proot's own ptrace-based process tracing can make
        // pty_bridge (see its SIGCHLD handling) legitimately wait on too. This wrapper never execs
        // the interactive shell — so its own EXIT trap actually gets to run afterward — and
        // SIGKILLs its entire process group the moment that shell exits, cleaning up any such
        // leftover daemon (it shares this process group by default unless it explicitly detached)
        // along with proot itself. Job control stays at its normal default (plain "-i"): Ctrl+Z
        // needs it to suspend a foreground command that's misbehaving.
        argv += listOf("-w", "/root", "/bin/sh", "-c", "trap 'kill -9 -- -\$\$ 2>/dev/null' EXIT; /bin/sh -i")

        val env = mapOf(
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            // Alpine's minirootfs ships no locale data at all (musl keeps locale support minimal
            // by design) — without LANG/LC_ALL set to *something* ending in "UTF-8", musl falls
            // back to the "C"/POSIX locale, and any locale-aware program (Python's click-based
            // CLIs refuse to start outright with "configured to use ASCII as encoding"; Node's own
            // interactive prompts, and any CLI agent tool built on either) either breaks or
            // mis-renders Unicode — box-drawing characters, emoji, wide CJK glyphs — even though
            // this terminal itself already decodes and sends real UTF-8 bytes throughout. musl
            // doesn't validate the locale name against installed locale data the way glibc does;
            // any name ending "UTF-8" is enough to turn on its multibyte-aware code paths.
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            // Advertised the same way a real 24-bit-color terminal does, so any CLI tool/agent
            // that checks this (a great many modern ones do, in preference to just trusting
            // TERM=xterm-256color) renders truecolor instead of falling back to a 256-color
            // approximation — this terminal already fully implements 38;2/48;2 truecolor SGR.
            "COLORTERM" to "truecolor",
            "ENV" to "/etc/profile", // ash sources this for interactive shells (see writeShellProfile)
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath, // proot's own libtalloc/libandroid-shmem live here
            "PROOT_LOADER" to File(nativeLibDir, "libalpineterm_proot_loader.so").absolutePath,
            "PROOT_TMP_DIR" to prootScratch.absolutePath,
            "PROOT_NO_SECCOMP" to "1", // proot's seccomp fast path mis-flags some apk-tools syscalls as EPERM
            // Alpine's official minirootfs tarball ships the combined CA bundle
            // (/etc/ssl/certs/ca-certificates.crt, a complete, healthy ~120-certificate Mozilla
            // bundle) but not the hash-named per-certificate symlinks a full install's
            // update-ca-certificates normally also generates in that same directory — apk-tools'
            // linked LibreSSL looks certificates up by that hash-named path by default, so with
            // none present it finds zero trusted roots and rejects every certificate chain
            // outright, identically, regardless of which host presented it. SSL_CERT_FILE is the
            // standard OpenSSL/LibreSSL override to verify against the flat bundle file directly
            // instead, which is intact and doesn't need the missing hash symlinks at all.
            "SSL_CERT_FILE" to "/etc/ssl/cert.pem",
            // Real terminal size for the initial prompt is already set via the pty's own
            // ioctl(TIOCSWINSZ) (see PtySession/pty_bridge) — ncurses apps (vim, htop, less)
            // query that directly and don't need these. They're only a fallback for the rare
            // script that reads $COLUMNS/$LINES instead of asking the tty, so it isn't stuck
            // assuming a default 80x24 the first time it runs, before any resize/SIGWINCH.
            "COLUMNS" to cols.toString(),
            "LINES" to rows.toString(),
        )
        return PtySession.start(bridge, context.cacheDir, rows, cols, argv, context.filesDir, env)
    }

    private fun startSystemShell(context: Context, bridge: File, rows: Int, cols: Int): PtySession {
        val env = mapOf(
            "HOME" to context.filesDir.absolutePath,
            "TERM" to "xterm-256color",
            "PATH" to "/system/bin:/system/xbin",
            "COLUMNS" to cols.toString(),
            "LINES" to rows.toString(),
        )
        return PtySession.start(bridge, context.cacheDir, rows, cols, listOf("/system/bin/sh"), context.filesDir, env)
    }

    /**
     * Runs `apk search` inside a fresh, one-off, non-interactive proot invocation — separate from
     * every tab's live interactive session, so a package search never intermixes with (or steals
     * input from) whatever the user is actually doing in their terminal. Blocks the calling
     * thread until the command exits; callers must already be off the UI thread.
     *
     * `apk update` runs first so results reflect Alpine's real, current remote index rather than
     * whatever was last synced (possibly at first install) — this is the actual trusted package
     * list, not a separate hardcoded/guessed one, at the cost of the search taking a few seconds
     * on a slow connection. Output is parsed from `apk search -q`, which prints one
     * `name-version-rN` per line with no description to strip out first.
     */
    fun searchPackages(context: Context, query: String): List<String> {
        if (query.isBlank() || !AlpineRootfs.isReady(context)) return emptyList()
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val bridge = File(nativeLibDir, "libpty_bridge.so")
        val proot = File(nativeLibDir, "libalpineterm_proot.so")
        if (!bridge.isFile || !proot.isFile) return emptyList()

        val root = AlpineRootfs.rootDir(context)
        writeResolvConf(context, root)
        writeApkHostsIPv4Only(root)
        val guestTmp = File(context.cacheDir, "guest-tmp").apply { mkdirs() }
        val prootScratch = File(context.cacheDir, "proot-scratch-search-${System.nanoTime()}").apply { mkdirs() }
        val storageRoot = StorageAccess.sharedStorageRoot()

        val argv = mutableListOf(
            proot.absolutePath, "-r", root.absolutePath, "-0", "-k", "3.10",
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${guestTmp.absolutePath}:/tmp",
        )
        if (StorageAccess.isGranted(context) && storageRoot.isDirectory) argv += listOf("-b", "${storageRoot.absolutePath}:/sdcard")
        argv += removableDriveBinds(context, root)
        // Single-quoted, with any embedded single quote closed/escaped/reopened — the standard
        // POSIX-shell-safe way to pass arbitrary user text through /bin/sh -c as one literal word.
        val safeQuery = "'" + query.replace("'", "'\\''") + "'"
        argv += listOf(
            "-w", "/root", "/bin/sh", "-c",
            "apk update >/dev/null 2>&1; apk search -q $safeQuery",
        )
        val env = mapOf(
            "HOME" to "/root",
            "TERM" to "dumb",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
            "PROOT_LOADER" to File(nativeLibDir, "libalpineterm_proot_loader.so").absolutePath,
            "PROOT_TMP_DIR" to prootScratch.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "SSL_CERT_FILE" to "/etc/ssl/cert.pem",
        )
        val session = PtySession.start(bridge, context.cacheDir, 24, 80, argv, context.filesDir, env)
        // readText() below blocks on `apk update`'s own network I/O with no timeout of its own —
        // a stalled/offline connection would otherwise wedge this call (and the single-thread
        // executor it runs on, and every proot process it never got to destroy()) forever. This
        // watchdog forces the issue by destroying the session after a generous timeout, which
        // closes its stdout and unblocks readText() with an IOException instead. Only destroys on
        // an actual timeout (sleep() completing) — the finally block below interrupts this thread
        // once readText() returns on its own, which just ends the thread quietly instead.
        val watchdog = Thread({
            try {
                Thread.sleep(SEARCH_TIMEOUT_MS)
                runCatching { session.destroy() }
            } catch (_: InterruptedException) {
                // Normal completion — readText() already finished, nothing to cancel.
            }
        }, "pty-search-watchdog").apply { isDaemon = true; start() }
        return try {
            session.stdout.bufferedReader().readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "package search failed", e)
            emptyList()
        } finally {
            watchdog.interrupt()
            session.destroy()
            prootScratch.deleteRecursively()
        }
    }

    private const val SEARCH_TIMEOUT_MS = 20_000L

    /** For the Settings "Refresh network" action — resolv.conf is only otherwise written when a
     *  new session starts, so a network change mid-session (wifi <-> mobile data) or a carrier
     *  DNS server that's stopped responding stays stuck until the user thinks to open a new tab.
     *  A no-op if Alpine was never actually set up (system-shell fallback has no rootfs). */
    fun refreshNetwork(context: Context) {
        if (!AlpineRootfs.isReady(context)) return
        val root = AlpineRootfs.rootDir(context)
        writeResolvConf(context, root)
        writeApkHostsIPv4Only(root)
    }

    /**
     * Refreshed every session start: the guest has no netd of its own to resolve DNS with.
     *
     * Always appends public fallback resolvers after whatever the host reports, rather than only
     * falling back to them when the host has none at all — a carrier's own DNS on mobile data is
     * often present but genuinely unreliable ("DNS: transient error"/"I/O error" mid-session is a
     * real, commonly hit case, not just a theoretical one), and musl's resolver (what Alpine's
     * apk and everything else here links against) tries each nameserver line in order on failure,
     * so a flaky first entry now has somewhere to fall through to instead of every lookup just
     * failing outright.
     */
    private fun writeResolvConf(context: Context, root: File) = synchronized(networkConfigLock) {
        runCatching {
            val servers = (NetworkInfo.dnsServers(context) + listOf("1.1.1.1", "8.8.8.8")).distinct()
            val resolv = File(root, "etc/resolv.conf")
            resolv.parentFile?.mkdirs()
            resolv.writeText(servers.take(3).joinToString("\n") { "nameserver $it" } + "\n")
        }.onFailure { Log.w(TAG, "could not write guest resolv.conf", it) }
    }

    /**
     * Confirmed root cause of a long "apk update: Permission denied" investigation on a real
     * device: dl-cdn.alpinelinux.org and its mirrors are dual-stack (both an A and an AAAA DNS
     * record), the network in question has no working IPv6 route at all (a raw connection to the
     * literal IPv6 address failed with "Network unreachable"), and apk-tools' own networking
     * doesn't fall back to the IPv4 address the way a browser or wget does — it tries the
     * unreachable IPv6 address and gives up outright, reporting that failure generically.
     *
     * Rather than trying to change apk-tools' own connection behavior (not something this app
     * controls) or disable IPv6 device-wide (impossible without root, and proot shares the real
     * network stack rather than a private namespace — nothing scoped to just the guest is even
     * possible), this resolves each apk-repository hostname to its IPv4 address using Android's
     * own resolver (which already handles this correctly — that's *why* wget/the browser work)
     * and writes those straight into the guest's /etc/hosts, which DNS lookups always consult
     * first. apk's own resolver then never sees an AAAA record for these hosts to begin with.
     */
    private fun writeApkHostsIPv4Only(root: File): Unit = synchronized(networkConfigLock) {
        runCatching {
            val repoFile = File(root, "etc/apk/repositories")
            if (!repoFile.isFile) return
            val hosts = repoFile.readLines()
                .mapNotNull { Regex("""^https?://([^/]+)/""").find(it.trim())?.groupValues?.get(1) }
                .distinct()
            if (hosts.isEmpty()) return

            // A begin/end marker pair, not a single marker with "everything after this line is
            // ours" — takeWhile-to-the-marker used to also discard any line a user appended to
            // /etc/hosts themselves *after* this block on a previous session, every single time
            // this function ran again (i.e. every session start).
            val beginMarker = "# BEGIN AlpDroid: force IPv4 for apk (this network has no IPv6 route)"
            val endMarker = "# END AlpDroid"
            val oldSingleMarker = "# added by AlpDroid: force IPv4 for apk (this network has no IPv6 route)"
            val hostsFile = File(root, "etc/hosts")
            val existingLines = if (hostsFile.isFile) hostsFile.readLines() else emptyList()
            val beginIdx = existingLines.indexOf(beginMarker)
            val endIdx = if (beginIdx >= 0) existingLines.indexOf(endMarker).takeIf { it > beginIdx } ?: -1 else -1
            val (before, after) = when {
                beginIdx >= 0 && endIdx >= 0 -> existingLines.subList(0, beginIdx) to existingLines.subList(endIdx + 1, existingLines.size)
                // One-time migration from the old single-marker format, which has no reliable end
                // point of its own — treated as "the old block ran to the end of the file", the
                // same assumption that format always made, rather than perpetuating it.
                else -> existingLines.takeWhile { it != oldSingleMarker } to emptyList()
            }
            val entries = hosts.mapNotNull { host ->
                val ipv4 = runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull()
                    ?.firstOrNull { it is java.net.Inet4Address }
                    ?.hostAddress
                ipv4?.let { "$it $host" }
            }
            if (entries.isEmpty()) return
            hostsFile.writeText((before + beginMarker + entries + endMarker + after).joinToString("\n") + "\n")
        }.onFailure { Log.w(TAG, "could not force IPv4 apk hosts", it) }
    }

    /** SD cards / USB drives mounted right now, bound at /mnt/<label> — a drive plugged in after a
     *  session started needs a new tab to appear (proot binds are fixed at launch). The guest
     *  mountpoint is created up front: proot won't invent it. */
    private fun removableDriveBinds(context: Context, root: File): List<String> =
        DeviceInfo.guestBinds(context).flatMap { (host, guest) ->
            File(root, guest.removePrefix("/")).mkdirs()
            listOf("-b", "${host.absolutePath}:$guest")
        }

    /** Just the prompt — every session opens straight onto a live cursor with no banner text
     *  ahead of it. Written as its own drop-in under /etc/profile.d/, not by overwriting
     *  /etc/profile itself the way this used to — that clobbered Alpine's own stock /etc/profile
     *  (which sets PATH, umask 022, and sources every script under /etc/profile.d/ — where an
     *  installed package's own environment setup lands) on literally every single session start,
     *  along with any edits the user made to /etc/profile themselves. */
    private fun writeShellProfile(root: File) {
        runCatching {
            val dropIn = File(root, "etc/profile.d/alpdroid-prompt.sh")
            dropIn.parentFile?.mkdirs()
            dropIn.writeText(
                // Bold green user@host, bold blue cwd — plain SGR color numbers (32/34) rather
                // than a fixed truecolor value, so the prompt follows whichever ANSI16 palette
                // the active theme currently defines instead of looking wrong against it.
                // Every escape wrapped in \[ \] (busybox ash turns those into its "ignore this
                // width" markers): without them the line editor counted the color codes (~11 chars)
                // as visible text, so it thought a wrapped history line was that much wider — it
                // forced its wrap too early and moved the cursor up the wrong number of rows on
                // redraw, leaving a stale copy of the line behind on every Up/Down arrow press.
                "PS1='\\[\u001B[1;32m\\]\\u@alpineterm\\[\u001B[0m\\]:\\[\u001B[1;34m\\]\\w\\[\u001B[0m\\]\\$ '\n" +
                    "export PS1\n",
            )
            // Guarantees the drop-in above (and everything else under /etc/profile.d/) actually
            // gets sourced — true out of the box for a fresh Alpine minirootfs, but an install
            // whose /etc/profile was already fully overwritten by an older version of this same
            // function has no such sourcing left in it at all. Appended once, never overwriting
            // anything already there, so a real stock /etc/profile (or the user's own edits to it)
            // is left completely alone from here on.
            val profile = File(root, "etc/profile")
            val existing = if (profile.isFile) profile.readText() else ""
            if (!existing.contains("profile.d")) {
                profile.parentFile?.mkdirs()
                val separator = if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else ""
                profile.writeText(existing + separator + "for i in /etc/profile.d/*.sh; do [ -r \"\$i\" ] && . \"\$i\"; done\n")
            }
            // A leftover from the old guarded-welcome-banner profile (now removed) — an
            // install set up before this change already has this marker sitting in /root and it
            // no longer does anything, so clean it up rather than leave dead debris behind.
            File(root, "root/.motd-shown").delete()
        }.onFailure { Log.w(TAG, "could not write guest shell profile", it) }
    }
}
