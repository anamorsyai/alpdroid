package com.alpdroid.browser

import org.json.JSONObject
import org.json.JSONTokener

/** What [EvalPlanner] needs from the page; the real one is backed by a WebView, tests use a JavaScript engine. */
interface EvalHost {
    /** Hands [script] to `evaluateJavascript`; returns its JSON result, or null when it threw or the page did not answer. */
    suspend fun raw(script: String): String?

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
        // 1. An expression, possibly a promise.
        val id = host.newId()
        if (host.raw(AutomationJs.evalExpression(id, script)) == MARK) return settle(host.report(id, timeoutMs), timeoutMs)

        // 2. Not an expression: is it statements at all?
        if (host.raw(AutomationJs.evalProbe(script)) != MARK) {
            return fail("the script could not be parsed (syntax error), or the page is not ready to run scripts: wait for it with `browserctl waitnav` and check the code")
        }
        // 3. Statements that return or await a value.
        if (AutomationJs.needsAsyncBody(script)) return host.asyncBody(AutomationJs.asyncBody(script), timeoutMs)

        // 4. Plain statements: the value of the last expression, as eval gives it.
        val sid = host.newId()
        val r = host.raw(AutomationJs.evalStatements(sid, script))
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

    private companion object { val MARK = "\"${AutomationJs.OK_MARK}\"" }
}
