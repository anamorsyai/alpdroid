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
 *   - a full [wipe] at process start — nothing can be using it yet, every guest is a child of this
 *     process (same reasoning as the proot-scratch sweep), and `/tmp` is never meant to persist; and
 *   - a budget-based [reap] on the resource tick: once the tree grows past a high-water mark the
 *     oldest top-level entries are removed (newest kept) until back under a low-water mark, but an
 *     entry with *any* file touched within a grace window is left alone — so a download or an
 *     extraction in progress into `/tmp` is never pulled out from under a running build.
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

    private data class Entry(val file: File, val bytes: Long, val newestMtime: Long)

    data class Plan(val remove: List<File>, val freedBytes: Long)

    /** Total bytes under [dir], not following symlinks (a link counts only as itself). */
    fun treeSize(dir: File): Long = (dir.listFiles() ?: emptyArray()).sumOf { measure(it).first }

    /**
     * Which top-level entries of [dir] to remove to bring it under [lowWater], oldest activity
     * first, skipping any entry with a file touched within [graceMs] of [now]. Empty when the tree
     * is already at or under [highWater]. Deletes nothing.
     */
    fun plan(dir: File, highWater: Long, lowWater: Long, graceMs: Long, now: Long): Plan {
        val children = dir.listFiles() ?: return Plan(emptyList(), 0)
        val entries = children.map { val (b, m) = measure(it); Entry(it, b, m) }
        var total = entries.sumOf { it.bytes }
        if (total <= highWater) return Plan(emptyList(), 0)

        val removable = entries
            .filter { now - it.newestMtime >= graceMs }  // leave anything with recent activity alone
            .sortedBy { it.newestMtime }                 // oldest activity first
        val remove = ArrayList<File>()
        var freed = 0L
        for (e in removable) {
            if (total <= lowWater) break
            remove += e.file
            freed += e.bytes
            total -= e.bytes
        }
        return Plan(remove, freed)
    }

    /** Runs [plan] against [dir] and deletes the chosen entries; returns bytes freed (as planned). */
    fun reap(
        dir: File,
        highWater: Long = HIGH_WATER_BYTES,
        lowWater: Long = LOW_WATER_BYTES,
        graceMs: Long = GRACE_MS,
        now: Long = System.currentTimeMillis(),
    ): Long {
        if (!dir.isDirectory) return 0
        val p = plan(dir, highWater, lowWater, graceMs, now)
        p.remove.forEach { runCatching { it.deleteRecursivelyNoFollow() } }
        return p.freedBytes
    }

    /** Empties [dir] (its contents, not the directory itself), not following symlinks. */
    fun wipe(dir: File) {
        if (!dir.isDirectory) return
        (dir.listFiles() ?: emptyArray()).forEach { runCatching { it.deleteRecursivelyNoFollow() } }
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
