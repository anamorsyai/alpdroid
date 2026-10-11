package com.alpdroid.browser

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The real eval plan against a real Chromium page that forbids `unsafe-eval` and requires Trusted Types — the pages that
 * refused the earlier implementation. Skipped unless Playwright's Node module can be found (set ALP_PLAYWRIGHT_NODE_PATH
 * to its node_modules folder; `/opt/node22/lib/node_modules` is tried by default).
 */
class EvalInChromiumTest {
    private var proc: Process? = null
    private val replies = ConcurrentHashMap<Int, LinkedBlockingQueue<Pair<Boolean, String?>>>()
    private val reports = ConcurrentHashMap<String, String>()
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private var n = 0
    private var ids = 0
    private lateinit var writer: java.io.Writer

    @Before fun start() {
        val nodePath = System.getenv("ALP_PLAYWRIGHT_NODE_PATH") ?: "/opt/node22/lib/node_modules"
        assumeTrue("Playwright is not installed", File(nodePath, "playwright").isDirectory)
        val script = File.createTempFile("chromium_host", ".js").also { it.deleteOnExit() }
        script.writeText(javaClass.getResource("/chromium_host.js")!!.readText())
        val pb = ProcessBuilder("node", script.absolutePath).redirectErrorStream(false)
        pb.environment()["NODE_PATH"] = nodePath
        val p = pb.start().also { proc = it }
        writer = p.outputStream.bufferedWriter()
        val ready = CountDownLatch(1)
        Thread {
            BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                val j = JSONObject(line)
                when {
                    j.has("ready") -> ready.countDown()
                    j.has("report") -> { val r = j.getJSONArray("report"); reports[r.getString(0)] = r.getString(1); latches.getOrPut(r.getString(0)) { CountDownLatch(1) }.countDown() }
                    j.has("reply") -> replies[j.getInt("reply")]?.add(!j.getBoolean("threw") to (if (j.isNull("value")) null else j.getString("value")))
                }
            }
        }.apply { isDaemon = true }.start()
        assumeTrue("Chromium did not start", ready.await(60, TimeUnit.SECONDS))
    }

    @After fun stop() { runCatching { writer.write("{\"op\":\"quit\"}\n"); writer.flush() }; proc?.destroyForcibly() }

    private fun call(script: String): Pair<Boolean, String?> {
        val id = n++
        val q = LinkedBlockingQueue<Pair<Boolean, String?>>().also { replies[id] = it }
        writer.write(JSONObject().put("op", "eval").put("n", id).put("script", script).toString() + "\n"); writer.flush()
        return q.poll(30, TimeUnit.SECONDS) ?: (false to null)
    }

    private val host = object : EvalHost {
        override suspend fun send(script: String) { call(script) }
        override suspend fun raw(script: String, timeoutMs: Long) = call(script)
        override fun newId() = "id${ids++}".also { latches.getOrPut(it) { CountDownLatch(1) } }
        override suspend fun report(id: String, timeoutMs: Long): String? {
            latches.getOrPut(id) { CountDownLatch(1) }.await(timeoutMs, TimeUnit.MILLISECONDS)
            return reports[id]
        }
        override suspend fun asyncBody(body: String, timeoutMs: Long): JSONObject {
            val id = newId()
            call(AutomationJs.asyncWrapper(id, body))
            val j = JSONObject(report(id, timeoutMs) ?: return JSONObject().put("ok", false).put("error", "no answer"))
            return if (j.optBoolean("ok")) JSONObject().put("ok", true).put("result", if (j.isNull("v")) JSONObject.NULL else j.get("v")) else JSONObject().put("ok", false).put("error", j.getString("error"))
        }
        override fun navigated() = false
    }

    private fun run(js: String) = runBlocking { EvalPlanner(host).run(js, 5_000) }

    @Test fun `the page really does forbid eval`() {
        val (_, r) = call("(()=>{try{return String((0,eval)('1+1'))}catch(e){return 'blocked: '+e.message}})()")
        assertTrue(r, r!!.contains("blocked"))
    }

    @Test fun `expressions, statements and promises work on a strict page`() {
        assertEquals(2, run("1+1").get("result"))
        assertEquals("Strict page", run("document.title").get("result"))
        assertEquals(6, run("const a = 2; const b = 3; a * b").get("result"))
        assertEquals(3, run("await Promise.resolve(3)").get("result"))
        assertEquals(5, run("const x = 2;\nreturn x + 3;").get("result"))
        assertEquals("a\n2 ب🐰", run("// عربي\nconst t = `a\n\${1+1} ب🐰`;\nt").get("result"))
        assertEquals(2, run("document.querySelectorAll('p').length").get("result"))
    }

    @Test fun `errors and syntax problems are told apart`() {
        val boom = run("throw new Error('boom')")
        assertFalse(boom.getBoolean("ok")); assertTrue(boom.getString("error").contains("boom"))
        val syn = run("this is not javascript (")
        assertFalse(syn.getBoolean("ok")); assertTrue(syn.getString("error"), syn.getString("error").contains("syntax error"))
    }
}
