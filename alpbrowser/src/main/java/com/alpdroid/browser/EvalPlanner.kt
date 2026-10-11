package com.alpdroid.browser

import org.json.JSONObject
import org.json.JSONTokener

/** What [EvalPlanner] needs from the page; the real one is backed by a WebView, tests use a JavaScript engine. */
interface EvalHost {
    /** Hands [script] to `evaluateJavascript` and does not wait for its return value. */
    suspend fun send(script: String)

    /** Hands [script] to `evaluateJavascript` and waits for its return value: (answered, JSON text or null). */
    suspend fun raw(script: String, timeoutMs: Long): Pair<Boolean, String?>

    /** A fresh call id whose report ([report]) can arrive at any time after it is issued. */
    fun newId(): String

    /** The JSON a script reported for [id] through the bridge, or null if nothing arrived within [timeoutMs]. */
    suspend fun report(id: String, timeoutMs: Long): String?

    /** Runs [body] as an async function body and returns its explicit `return` value. */
    suspend fun asyncBody(body: String, timeoutMs: Long): JSONObject

    /** True if the page navigated since the call started. */
    fun navigated(): Boolean
}

/** Chooses how to run an `eval` script so that it works whatever the page's Content-Security-Policy says. See [AutomationJs.evalExpression]. */
class EvalPlanner(private val host: EvalHost) {
    suspend fun run(script: String, timeoutMs: Long): JSONObject {
        val id = host.newId(); val ack1 = host.newId(); val ack2 = host.newId()
        // 1 + 2. Send the expression form and, right behind it, the statements probe.
        host.send(AutomationJs.evalExpression(id, ack1, script))
        host.send(AutomationJs.evalProbe(ack2, script))
        if (host.report(ack2, ACK_MS) == null) {
            // Neither compiled, or the page did not answer at all: a ping tells the two apart.
            val ping = host.newId()
            host.send(AutomationJs.ping(ping))
            return if (host.report(ping, ACK_MS) != null) fail("the script could not be parsed (syntax error): check the code")
            else fail("the page did not answer within ${ACK_MS}ms: it may still be loading, or has no script access. Wait with `browserctl waitnav`, then retry")
        }
        if (host.report(ack1, 300) != null) return settle(host.report(id, timeoutMs), timeoutMs)

        // 3. Statements that return or await a value.
        if (AutomationJs.needsAsyncBody(script)) return host.asyncBody(AutomationJs.asyncBody(script), timeoutMs)

        // 4. Plain statements: the value of the last expression, as eval gives it.
        val sid = host.newId()
        val (answered, r) = host.raw(AutomationJs.evalStatements(sid, script), minOf(timeoutMs, 10_000))
        if (!answered) return fail("the page ran the script but returned no value (it may have navigated): retry after `browserctl waitnav`")
        if (r == "\"${AutomationJs.ERR_MARK}\"") return settle(host.report(sid, 2_000), 2_000)
        return JSONObject().put("ok", true).put("result", value(r))
    }

    private fun settle(raw: String?, timeoutMs: Long): JSONObject {
        if (raw == null) {
            return fail(
                if (host.navigated()) "the page navigated while the script was running (no answer within ${timeoutMs}ms): wait for the navigation, then retry"
                else "no answer from the page within ${timeoutMs}ms: the script may still be running (a promise that never settles), or the page has no script access",
            )
        }
        val j = JSONObject(raw)
        if (!j.optBoolean("ok")) return fail(j.optString("error", "script failed"))
        return JSONObject().put("ok", true).put("result", if (j.isNull("v")) JSONObject.NULL else j.get("v"))
    }

    private fun value(raw: String?): Any = if (raw == null || raw == "null") JSONObject.NULL else runCatching { JSONTokener(raw).nextValue() }.getOrDefault(raw)

    private fun fail(message: String) = JSONObject().put("ok", false).put("error", message)

    private companion object { const val ACK_MS = 3_000L }
}
