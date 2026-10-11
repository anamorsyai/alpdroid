package com.alpdroid.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmpReaperTest {
    private fun tmpDir(): File = File.createTempFile("tmpreaper", "").let { it.delete(); it.mkdirs(); it }

    private fun file(dir: File, name: String, bytes: Int, mtime: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(mtime) }

    @Test fun treeSizeSumsAllFilesRecursively() {
        val dir = tmpDir()
        file(dir, "a", 1000, 0)
        val sub = File(dir, "sub").apply { mkdirs() }
        file(sub, "b", 2000, 0)
        assertEquals(3000L, TmpReaper.treeSize(dir))
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun underHighWaterRemovesNothing() {
        val dir = tmpDir()
        file(dir, "a", 1000, 0)
        val plan = TmpReaper.plan(dir, highWater = 5000, lowWater = 2000, graceMs = 0, now = 10_000, minEntryBytes = 0)
        assertTrue(plan.remove.isEmpty())
        assertEquals(0L, plan.plannedBytes)
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun overHighWaterTrimsOldestFirstDownToLowWater() {
        val dir = tmpDir()
        file(dir, "old", 4000, 1_000)   // oldest
        file(dir, "mid", 4000, 2_000)
        file(dir, "new", 4000, 3_000)   // newest, kept
        val now = 1_000_000L
        // total 12000, high 10000, low 5000: must drop to <=5000 -> remove old+mid (keeps new=4000)
        val plan = TmpReaper.plan(dir, highWater = 10_000, lowWater = 5_000, graceMs = 0, now = now, minEntryBytes = 0)
        assertEquals(listOf("old", "mid"), plan.remove.map { it.name })
        assertEquals(8000L, plan.plannedBytes)
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun recentlyTouchedEntriesAreNeverRemoved() {
        val dir = tmpDir()
        val now = 1_000_000L
        file(dir, "fresh", 9000, now - 1000)      // within grace -> protected even though huge
        file(dir, "stale", 2000, now - 60_000)
        val plan = TmpReaper.plan(dir, highWater = 5000, lowWater = 1000, graceMs = 10_000, now = now, minEntryBytes = 0)
        // Only "stale" is eligible; removing it still leaves 9000 > lowWater, but fresh is untouchable.
        assertEquals(listOf("stale"), plan.remove.map { it.name })
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun directoryFreshnessUsesNewestFileInside() {
        val dir = tmpDir()
        val now = 1_000_000L
        val d = File(dir, "builddir").apply { mkdirs(); setLastModified(now - 90_000) }
        file(d, "part", 8000, now - 500)          // active file inside an "old" dir
        file(dir, "junk", 8000, now - 90_000)
        val plan = TmpReaper.plan(dir, highWater = 10_000, lowWater = 1000, graceMs = 10_000, now = now, minEntryBytes = 0)
        // builddir is protected by its fresh inner file; only junk can go.
        assertEquals(listOf("junk"), plan.remove.map { it.name })
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun tinyLiveEntriesAreNeverTouched() {
        val dir = tmpDir()
        val now = 10_000_000_000L
        // A small, old directory (think /tmp/tmux-0 holding a socket/lock) must survive even though it is
        // old and the tree is over budget; only the genuinely large junk goes.
        val tmux = File(dir, "tmux-0").apply { mkdirs() }
        file(tmux, "default", 10, now - 3_600_000)
        tmux.setLastModified(now - 3_600_000)
        file(dir, "big-junk", 9000, now - 3_600_000)
        val plan = TmpReaper.plan(dir, highWater = 5000, lowWater = 1000, graceMs = 10_000, now = now, minEntryBytes = 1000)
        assertEquals(listOf("big-junk"), plan.remove.map { it.name })
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun reapActuallyDeletesPlannedEntriesAndReportsRealBytes() {
        val dir = tmpDir()
        val old = file(dir, "old", 6000, 1000)
        val new = file(dir, "new", 6000, 2000)
        val freed = TmpReaper.reap(dir, highWater = 8000, lowWater = 6000, graceMs = 0, now = 1_000_000L, minEntryBytes = 0)
        assertEquals(6000L, freed)
        assertFalse(old.exists())
        assertTrue(new.exists())
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun readOnlyDirectoriesAreUnlockedAndRemoved() {
        val dir = tmpDir()
        // Go's module cache is 0555: an unprivileged uid cannot delete the children of a dir it cannot write.
        val ro = File(dir, "gomod").apply { mkdirs() }
        file(ro, "pkg", 6000, 1000)
        ro.setLastModified(1000) // a directory's own mtime counts as activity too
        ro.setWritable(false, false)
        val freed = TmpReaper.reap(dir, highWater = 1000, lowWater = 0, graceMs = 0, now = 1_000_000L, minEntryBytes = 0)
        assertFalse("read-only tree must really be gone", ro.exists())
        assertEquals(6000L, freed)
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun wipeEmptiesButKeepsTheDirectory() {
        val dir = tmpDir()
        file(dir, "a", 100, 0)
        File(dir, "sub").apply { mkdirs() }.let { file(it, "b", 100, 0) }
        TmpReaper.wipe(dir)
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.listFiles()!!.size)
        dir.deleteRecursivelyNoFollow()
    }

    @Test fun retireMovesContentsAsideInstantlyAndLeavesFreshEmptyDir() {
        val cache = tmpDir()
        val guest = File(cache, "guest-tmp").apply { mkdirs() }
        file(guest, "junk", 5000, 0)
        File(guest, "sub").apply { mkdirs() }.let { file(it, "deep", 5000, 0) }

        val retired = TmpReaper.retire(guest)

        assertNotNull(retired)
        assertTrue("a fresh empty /tmp must be ready for sessions at once", guest.isDirectory)
        assertEquals(0, guest.listFiles()!!.size)
        assertTrue("the old contents wait in the retired directory", File(retired!!, "junk").isFile)
        assertTrue(File(File(retired, "sub"), "deep").isFile)

        TmpReaper.sweepRetired(cache)
        assertFalse("the lazy sweep removes the retired tree", retired.exists())
        assertTrue(guest.isDirectory)
        cache.deleteRecursivelyNoFollow()
    }

    @Test fun retireOfAnEmptyOrMissingDirIsANoop() {
        val cache = tmpDir()
        val guest = File(cache, "guest-tmp")
        assertNull(TmpReaper.retire(guest))          // missing: created, nothing to retire
        assertTrue(guest.isDirectory)
        assertNull(TmpReaper.retire(guest))          // empty: nothing to retire
        assertEquals(1, cache.listFiles()!!.size)    // no stray retired dirs
        cache.deleteRecursivelyNoFollow()
    }

    @Test fun sweepRetiredAlsoClearsLeftoversFromADeadProcess() {
        val cache = tmpDir()
        val leftover = File(cache, "guest-tmp.old.12345").apply { mkdirs() }
        file(leftover, "x", 100, 0)
        val keep = File(cache, "unrelated").apply { mkdirs() }
        TmpReaper.sweepRetired(cache)
        assertFalse(leftover.exists())
        assertTrue("only retired dirs are touched", keep.exists())
        cache.deleteRecursivelyNoFollow()
    }
}
