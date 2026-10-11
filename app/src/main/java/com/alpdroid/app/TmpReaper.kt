package com.alpdroid.app

import java.io.File
import java.nio.file.Files

/**
 * Keeps the guest `/tmp` (cacheDir/guest-tmp, bound into Alpine) from filling the phone.
 *
 * On a real Linux box `/tmp` is wiped on every boot and trimmed by a periodic cleaner, so programs
 * treat it as free scratch space they never have to clean up. AlpDroid's process can instead run
 * for days, and nothing cleared this directory — opencode/bun/npm/pip download and build junk
 * written to `/tmp` accumulated without bound (a user reported 65 GB of "cache" filling the phone,
 * since cacheDir counts towards Android's app-cache figure). Two defenses:
 *
 *   - at process start the whole directory is [retire]d: renamed aside in one instant syscall (so
 *     nothing can be deleted under a live process and no session start waits on a huge delete) and
 *     emptied lazily by [sweepRetired] on a low-priority thread; and
 *   - a budget-based [reap] on the resource tick: once the tree grows past a high-water mark the
 *     oldest sizeable top-level entries are removed (newest kept) until back under a low-water mark,
 *     but an entry with *any* file touched within a grace window is left alone — so a download or an
 *     extraction in progress into `/tmp` is never pulled out from under a running build — and tiny
 *     entries (sockets, lock dirs, pid files: `/tmp/tmux-0`, an ssh-agent socket) are never touched,
 *     since removing them frees nothing and breaks a live program.
 *
 * The [plan] is pure (computes what to remove, deletes nothing) so it is unit-tested directly.
 */
object TmpReaper {
    /** Default budget: start trimming past 3 GiB, trim back down to 1 GiB. Generous enough that an
     *  ordinary build's scratch space is never touched; a runaway only reaches these after leaking. */
    const val HIGH_WATER_BYTES = 3L * 1024 * 1024 * 1024
    const val LOW_WATER_BYTES = 1L * 1024 * 1024 * 1024

    /** Never remove an entry with a file modified within this window — it is likely in active use. */
    const val GRACE_MS = 15L * 60 * 1000

    /** Entries smaller than this are never trimmed: they free next to nothing and are exactly the
     *  small live things (unix sockets, lock/pid files, tmux/ssh-agent dirs) a running program needs. */
    const val MIN_ENTRY_BYTES = 1L * 1024 * 1024

    private const val RETIRED_PREFIX = "guest-tmp.old."

    private data class Entry(val file: File, val bytes: Long, val newestMtime: Long)

    data class Plan(val remove: List<File>, val plannedBytes: Long)

    /** Total bytes under [dir], not following symlinks (a link counts only as itself). */
    fun treeSize(dir: File): Long = (dir.listFiles() ?: emptyArray()).sumOf { measure(it).first }

    /**
     * Which top-level entries of [dir] to remove to bring it under [lowWater], oldest activity
     * first, skipping any entry with a file touched within [graceMs] of [now] and any entry under
     * [minEntryBytes]. Empty when the tree is already at or under [highWater]. Deletes nothing.
     */
    fun plan(
        dir: File, highWater: Long, lowWater: Long, graceMs: Long, now: Long,
        minEntryBytes: Long = MIN_ENTRY_BYTES,
    ): Plan {
        val children = dir.listFiles() ?: return Plan(emptyList(), 0)
        val entries = children.map { val (b, m) = measure(it); Entry(it, b, m) }
        var total = entries.sumOf { it.bytes }
        if (total <= highWater) return Plan(emptyList(), 0)

        val removable = entries
            .filter { now - it.newestMtime >= graceMs }   // leave anything with recent activity alone
            .filter { it.bytes >= minEntryBytes }          // never bother with (or break) tiny live entries
            .sortedBy { it.newestMtime }                   // oldest activity first
        val remove = ArrayList<File>()
        var planned = 0L
        for (e in removable) {
            if (total <= lowWater) break
            remove += e.file
            planned += e.bytes
            total -= e.bytes
        }
        return Plan(remove, planned)
    }

    /**
     * Runs [plan] against [dir] and deletes the chosen entries. Returns the bytes ACTUALLY freed — an
     * entry that could not be fully removed (a read-only tree it could not unlock) counts for nothing,
     * so the caller's size figure stays honest instead of drifting from reality.
     */
    fun reap(
        dir: File,
        highWater: Long = HIGH_WATER_BYTES,
        lowWater: Long = LOW_WATER_BYTES,
        graceMs: Long = GRACE_MS,
        now: Long = System.currentTimeMillis(),
        minEntryBytes: Long = MIN_ENTRY_BYTES,
    ): Long {
        if (!dir.isDirectory) return 0
        var freed = 0L
        for (f in plan(dir, highWater, lowWater, graceMs, now, minEntryBytes).remove) {
            val bytes = measure(f).first
            if (forceDelete(f)) freed += bytes
        }
        return freed
    }

    /** Empties [dir] (its contents, not the directory itself), not following symlinks. */
    fun wipe(dir: File) {
        if (!dir.isDirectory) return
        (dir.listFiles() ?: emptyArray()).forEach { runCatching { forceDelete(it) } }
    }

    /**
     * Moves the current contents of [dir] out of the way in ONE rename and leaves a fresh empty [dir]
     * behind. A rename within one directory is instant whatever the size, so this can run synchronously
     * at process start — before any proot/plugin job can bind-mount `/tmp` — instead of a long recursive
     * delete that either blocked session start or deleted files under live processes. Returns the
     * retired directory, or null when there was nothing to retire or the rename failed (the budget
     * reaper then handles the old contents as before).
     */
    fun retire(dir: File): File? {
        if (!dir.isDirectory) { dir.mkdirs(); return null }
        if (dir.list().isNullOrEmpty()) return null
        val parent = dir.parentFile ?: return null
        val retired = File(parent, "$RETIRED_PREFIX${System.nanoTime()}")
        if (!dir.renameTo(retired)) return null
        dir.mkdirs()
        return retired
    }

    /** Deletes every directory [retire] left in [cacheDir] — also leftovers from an earlier process that died mid-delete. */
    fun sweepRetired(cacheDir: File) {
        (cacheDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith(RETIRED_PREFIX) }
            .forEach { runCatching { forceDelete(it) } }
    }

    /** Like [deleteRecursivelyNoFollow] but also unlocks read-only directories (Go's module cache is 0555,
     *  some npm/cargo trees too) — an unprivileged uid cannot delete the children of one it cannot write.
     *  Returns whether [f] is really gone. */
    internal fun forceDelete(f: File): Boolean {
        if (Files.isSymbolicLink(f.toPath())) return f.delete()
        if (f.isDirectory) {
            f.setWritable(true, true)
            f.setExecutable(true, true)
            f.setReadable(true, true)
            (f.listFiles() ?: emptyArray()).forEach { forceDelete(it) }
        }
        return f.delete() || !f.exists()
    }

    /** (bytes, newest mtime) of [f], recursing into real directories only — symlinks count as the
     *  link itself and are never walked, so this can never follow `~/storage -> /sdcard` out of the
     *  sandbox or loop on a cyclic link. */
    private fun measure(f: File): Pair<Long, Long> {
        if (Files.isSymbolicLink(f.toPath())) return f.length() to f.lastModified()
        if (f.isDirectory) {
            var bytes = 0L
            var newest = f.lastModified()
            (f.listFiles() ?: emptyArray()).forEach {
                val (b, m) = measure(it)
                bytes += b
                if (m > newest) newest = m
            }
            return bytes to newest
        }
        return f.length() to f.lastModified()
    }
}
