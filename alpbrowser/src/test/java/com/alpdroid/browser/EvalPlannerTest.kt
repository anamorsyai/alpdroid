package com.alpdroid.browser

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the real `eval` plan against a JavaScript engine (Node) that behaves like `evaluateJavascript`: a script that does
 * not compile answers null, one that does answers its completion value, and `__alpbridge.done` is available. `eval` and
 * `Function` are switched off in that page, which is what a strict Content-Security-Policy does.
 */
class EvalPlannerTest {
    private var reports = HashMap<String, String>()
    private var navigated = false
    private var n = 0

    private val harness = File.createTempFile("evalharness", ".js").also { it.deleteOnExit() }.apply {
        writeText("""
const vm=require('vm');
const src=require('fs').readFileSync(0,'utf8');
const reports=[];
const ctx=vm.createContext({__alpbridge:{done:(id,j)=>reports.push([id,j])},console,Promise,JSON,setTimeout,Date,Math});
vm.runInContext('globalThis.eval=function(){throw new EvalError("CSP: unsafe-eval is not allowed")};globalThis.Function=function(){throw new EvalError("CSP: unsafe-eval is not allowed")};',ctx);
let res=null;
try{const r=new vm.Script(src).runInContext(ctx);res=(r===undefined)?null:JSON.stringify(r);}catch(e){res=null;}
setTimeout(()=>{process.stdout.write(JSON.stringify({res:res,reports:reports}))},150);
""")
    }

    @Before fun needNode() {
        val ok = runCatching { ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("node is not installed", ok)
    }

    private val host = object : EvalHost {
        private fun exec(script: String): String? {
            val p = ProcessBuilder("node", harness.absolutePath).start()
            p.outputStream.use { it.write(script.toByteArray()) }
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor(30, TimeUnit.SECONDS)
            val j = JSONObject(out)
            val r = j.getJSONArray("reports")
            for (i in 0 until r.length()) reports[r.getJSONArray(i).getString(0)] = r.getJSONArray(i).getString(1)
            return if (j.isNull("res")) null else j.getString("res")
        }
        override suspend fun send(script: String) { exec(script) }
        override suspend fun raw(script: String, timeoutMs: Long) = true to exec(script)
        override fun newId() = "id${n++}"
        override suspend fun report(id: String, timeoutMs: Long) = reports[id]
        override suspend fun asyncBody(body: String, timeoutMs: Long): JSONObject {
            val id = newId()
            send(AutomationJs.asyncWrapper(id, body))
            val j = JSONObject(reports[id] ?: return JSONObject().put("ok", false).put("error", "no answer"))
            return if (j.optBoolean("ok")) JSONObject().put("ok", true).put("result", if (j.isNull("v")) JSONObject.NULL else j.get("v"))
            else JSONObject().put("ok", false).put("error", j.getString("error"))
        }
        override fun navigated() = navigated
    }

    private fun run(js: String) = runBlocking { EvalPlanner(host).run(js, 1000) }

    @Test fun `an expression, a promise and await work with eval switched off`() {
        assertEquals(2, run("1+1").get("result"))
        assertEquals("hello", run("document_title_stub = 'hello'; document_title_stub").get("result"))
        assertEquals(7, run("Promise.resolve(3).then(x=>x+4)").get("result"))
        assertEquals(9, run("await Promise.resolve(9)").get("result"))
        assertEquals("[1,2]", run("[1,2]").get("result").toString().replace(" ", ""))
    }

    @Test fun `statements give the value of the last expression, like eval`() {
        assertEquals(6, run("const a = 2; const b = 3; a * b").get("result"))
        assertEquals(JSONObject.NULL, run("let z = 1;").get("result"))
        val r = run("var o = {k: [1, {m: 'ü'}]}; o")
        assertEquals("ü", r.getJSONObject("result").getJSONArray("k").getJSONObject(1).getString("m"))
    }

    @Test fun `statements with return or await run as a function body`() {
        assertEquals(5, run("const x = 2;\nreturn x + 3;").get("result"))
        assertEquals(4, run("const v = await Promise.resolve(4);\nreturn v;").get("result"))
    }

    @Test fun `multi-line scripts with comments, template literals and non-ASCII text`() {
        val js = "// عربي 🐰\nconst t = `a\n\${1+1}b`; // trailing\nt"
        assertEquals("a\n2b", run(js).get("result"))
    }

    @Test fun `a thrown error is reported with its message and a syntax error says so`() {
        val e = run("throw new Error('boom')")
        assertFalse(e.getBoolean("ok")); assertTrue(e.getString("error").contains("boom"))
        val e2 = run("const q = 1; null.x")
        assertFalse(e2.getBoolean("ok")); assertTrue(e2.getString("error"), e2.getString("error").contains("null"))
        val s = run("this is not javascript (")
        assertFalse(s.getBoolean("ok")); assertTrue(s.getString("error").contains("syntax error"))
    }

    @Test fun `a script that never settles reports a timeout, naming a navigation when there was one`() {
        val e = run("new Promise(()=>{})")
        assertFalse(e.getBoolean("ok")); assertTrue(e.getString("error").contains("no answer"))
        navigated = true
        assertTrue(run("new Promise(()=>{})").getString("error").contains("navigated"))
    }

    @Test fun `the plan never uses eval or Function`() {
        for (s in listOf(AutomationJs.evalExpression("x", "y", "1"), AutomationJs.evalProbe("y", "1"), AutomationJs.evalStatements("x", "1"))) {
            assertFalse(s, Regex("\\beval\\b|\\bFunction\\b|createElement").containsMatchIn(s))
        }
    }
}
