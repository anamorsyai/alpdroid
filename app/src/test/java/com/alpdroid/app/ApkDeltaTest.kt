package com.alpdroid.app

import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** The delta made by the real CI script (scripts/make_apk_delta.py) applied by the real on-device code. */
class ApkDeltaTest {
    private lateinit var dir: File
    private val script = listOf("../scripts/make_apk_delta.py", "scripts/make_apk_delta.py").map { File(it) }.firstOrNull { it.isFile }

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("apkdelta").toFile()
        assumeTrue("python3 or the delta script is missing", script != null && runCatching { ProcessBuilder("python3", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    /** Builds a pair of "APKs": compressed-looking random data, then the next release with edits, a shift and an addition. */
    private fun pair(): Pair<ByteArray, ByteArray> {
        val r = Random(7)
        val old = ByteArray(600_000).also { r.nextBytes(it) }
        val edited = old.copyOf()
        r.nextBytes(ByteArray(5_000).also { chunk -> chunk.copyInto(edited, 200_000) })       // changed region
        val fresh = ByteArray(30_000).also { r.nextBytes(it) }
        val new = edited.copyOfRange(0, 350_000) + fresh + edited.copyOfRange(350_000, edited.size) + ByteArray(1_000) { 9 }  // moved tail
        return old to new
    }

    private fun makePatch(old: File, new: File): File {
        val out = File(dir, "p.adp")
        val p = ProcessBuilder("python3", "-I", script!!.absolutePath, old.absolutePath, new.absolutePath, out.absolutePath).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(log, p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
        return out
    }

    @Test fun `the delta rebuilds the new APK byte for byte and is far smaller`() {
        val (o, n) = pair()
        val old = file("old.apk", o); val new = file("new.apk", n)
        val patch = makePatch(old, new)
        assertTrue("patch ${patch.length()} vs ${n.size}", patch.length() < n.size / 5)
        val out = File(dir, "out.apk")
        val progress = ArrayList<Long>()
        ApkDelta.apply(old, patch, out, expectedSha256 = sha(n)) { done, _ -> progress += done }
        assertEquals(sha(n), sha(out.readBytes()))
        assertEquals(n.size.toLong(), progress.last())
        assertEquals(sha(n), ApkDelta.targetSha256(patch))
    }

    @Test fun `a different installed APK is refused and nothing is left behind`() {
        val (o, n) = pair()
        val patch = makePatch(file("old.apk", o), file("new.apk", n))
        val other = file("other.apk", o.copyOf().also { it[10] = (it[10] + 1).toByte() })
        val out = File(dir, "out.apk")
        val e = runCatching { ApkDelta.apply(other, patch, out) }.exceptionOrNull()
        assertTrue(e is ApkDelta.DeltaException && e.message!!.contains("not the one"))
        assertFalse(out.exists())
    }

    @Test fun `a damaged, truncated or mislabelled delta is refused`() {
        val (o, n) = pair()
        val old = file("old.apk", o)
        val patch = makePatch(old, file("new.apk", n))
        val bytes = patch.readBytes()
        val out = File(dir, "out.apk")
        fun refused(b: ByteArray, expected: String = "") {
            val f = file("bad.adp", b)
            assertTrue(runCatching { ApkDelta.apply(old, f, out, expected) }.exceptionOrNull() is ApkDelta.DeltaException)
            assertFalse(out.exists())
        }
        refused(bytes.copyOf(bytes.size / 2))                                                              // truncated
        refused(bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2] + 1).toByte() })           // a flipped byte
        refused(bytes.copyOf().also { it[0] = 'X'.code.toByte() })                                         // not a delta
        refused(bytes, expected = "0".repeat(64))                                                          // for another release
        refused(ByteArray(0))
    }

    @Test fun `findDelta picks the file made for the installed version only`() {
        fun asset(name: String, size: Long = 100) = JSONObject().put("name", name).put("size", size).put("url", "api/$name").put("browser_download_url", "dl/$name").put("digest", "sha256:" + "a".repeat(64))
        val assets = JSONArray().put(asset("AlpDroid-v1.7.82.apk", 2_000_000)).put(asset("AlpDroid-v1.7.82-from-1.7.80.adp")).put(asset("AlpDroid-v1.7.82-from-1.7.81.adp"))
        assertEquals("AlpDroid-v1.7.82-from-1.7.81.adp", AppUpdater.findDelta(assets, "1.7.81")!!.name)
        assertEquals("AlpDroid-v1.7.82-from-1.7.80.adp", AppUpdater.findDelta(assets, "v1.7.80")!!.name)
        assertEquals("a".repeat(64), AppUpdater.findDelta(assets, "1.7.81")!!.sha256)
        assertNull(AppUpdater.findDelta(assets, "1.7.79"))      // further behind than the deltas CI made: full download
        assertNull(AppUpdater.findDelta(assets, "1.7.8"))       // a name that merely ends the same way must not match
        assertNull(AppUpdater.findDelta(assets, null))
        assertNull(AppUpdater.findDelta(null, "1.7.81"))
        assertNotNull(AppUpdater.findDelta(assets, " 1.7.81 "))
    }

    /** Optional: set ALP_REAL_APKS to a folder holding two consecutive release APKs (old.apk, new.apk) to check them for real. */
    @Test fun `two real consecutive releases rebuild exactly`() {
        val real = System.getenv("ALP_REAL_APKS")?.let { File(it) }
        assumeTrue("ALP_REAL_APKS is not set", real != null && File(real, "old.apk").isFile && File(real, "new.apk").isFile)
        val old = File(real, "old.apk"); val new = File(real, "new.apk")
        val patch = makePatch(old, new)
        val out = File(dir, "out.apk")
        ApkDelta.apply(old, patch, out, expectedSha256 = sha(new.readBytes()))
        assertEquals(sha(new.readBytes()), sha(out.readBytes()))
        println("real APK delta: ${patch.length()} of ${new.length()} bytes")
    }
}
