package com.alpdroid.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.alpdroid.browser.ui.browser.ensureTabWebView
import com.alpdroid.browser.ui.browser.normalizeUrl
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONObject

/**
 * Drives the front tab for the control API: element commands, waiting for navigation, screenshots and cookies — so an
 * agent needs no Playwright. Page-side work is JavaScript run through [callAsync]: `evaluateJavascript` cannot await a
 * promise, so each script reports back through [JsBridge] when it settles. Every result is JSON with `"ok"` first;
 * failures are `{"ok":false,"error":...}` so a caller can fail loudly.
 */
class PageAutomation(private val app: AlpBrowserApp) : PageControl {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val random = SecureRandom()

    /** Exposed to pages as `__alpbridge`. Only completes a call whose (random, unguessable) id this process issued. */
    inner class JsBridge {
        @JavascriptInterface fun done(id: String, json: String) { pending[id]?.complete(json) }
    }
    val jsBridge = JsBridge()

    private fun fail(message: String) = JSONObject().put("ok", false).put("error", message)

    private suspend fun front(): Pair<BrowserTab, WebView>? = withContext(Dispatchers.Main) {
        val tab = app.browserWebViewHolder.active ?: return@withContext null
        tab to ensureTabWebView(app, app, tab)
    }

    /** Runs [body] (an async function body) in the page and waits for it to settle. */
    private suspend fun callAsync(wv: WebView, body: String, timeoutMs: Long, tab: BrowserTab? = null, wrap: Boolean = false): JSONObject {
        val seq0 = tab?.navSeq
        val id = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val done = CompletableDeferred<String>()
        pending[id] = done
        try {
            withContext(Dispatchers.Main) { wv.evaluateJavascript(AutomationJs.asyncWrapper(id, body), null) }
            val raw = withTimeoutOrNull(timeoutMs) { done.await() }
                ?: return fail(
                    if (tab != null && tab.navSeq != seq0) "the page navigated while the command was running (no answer within ${timeoutMs}ms): wait for the navigation, then retry"
                    else "no answer from the page within ${timeoutMs}ms: it may still be loading, or it has no script access")
            val j = JSONObject(raw)
            if (!j.optBoolean("ok")) return fail(j.optString("error", "script failed"))
            val out = JSONObject().put("ok", true)
            val v = j.opt("v")
            if (v is JSONObject && !wrap) v.keys().forEach { out.put(it, v.get(it)) } else out.put("result", v ?: JSONObject.NULL)
            return out
        } finally { pending.remove(id) }
    }

    // ------------------------------------------------------------------------------------------------- commands

    override fun command(args: JSONObject): JSONObject = runBlocking {
        val (tab, wv) = front() ?: return@runBlocking fail("no page is open")
        val timeout = args.optLong("timeout", AutomationJs.DEFAULT_TIMEOUT_MS)
        if (args.optString("cmd") in setOf("click", "fill", "type", "text", "attr", "exists", "waitfor", "html") && args.optString("selector").isBlank() && args.optString("cmd") != "html") {
            return@runBlocking fail("selector required")
        }
        callAsync(wv, AutomationJs.commandBody(args), timeout + 4_000, tab)
    }

    override fun eval(js: String, await: Boolean, timeoutMs: Long): JSONObject = runBlocking {
        val (tab, wv) = front() ?: return@runBlocking fail("no page is open")
        val seq0 = tab.navSeq
        val host = object : EvalHost {
            override suspend fun send(script: String) {
                withContext(Dispatchers.Main) { wv.evaluateJavascript(script, null) }
            }
            override suspend fun raw(script: String, timeoutMs: Long): Pair<Boolean, String?> {
                var value: String? = null
                val answered = withTimeoutOrNull(timeoutMs) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<Unit> { c -> wv.evaluateJavascript(script) { r -> value = r; if (c.isActive) c.resume(Unit) } }
                    }
                } != null
                return answered to value
            }
            override fun newId(): String {
                val id = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                pending[id] = CompletableDeferred()
                return id
            }
            override suspend fun report(id: String, timeoutMs: Long): String? =
                try { pending[id]?.let { d -> withTimeoutOrNull(timeoutMs) { d.await() } } } finally { pending.remove(id) }
            override suspend fun asyncBody(body: String, timeoutMs: Long): JSONObject {
                return callAsync(wv, body, timeoutMs, tab, wrap = true)
            }
            override fun navigated() = tab.navSeq != seq0
        }
        EvalPlanner(host).run(js, timeoutMs)
    }

    // ------------------------------------------------------------------------------------------------- navigation

    private fun report(tab: BrowserTab, requested: String?, state: String, startedAt: Long, navigated: Boolean = true): JSONObject {
        val status = if (tab.httpStatus > 0) tab.httpStatus else if (tab.lastError == null) 200 else 0
        return JSONObject().put("ok", tab.lastError == null || navigated.not()).put("state", state).put("navigated", navigated)
            .put("url", tab.url).put("title", tab.title).put("status", status)
            .put("redirected", requested != null && stripSlash(requested) != stripSlash(tab.url))
            .put("elapsedMs", System.currentTimeMillis() - startedAt)
            .also { if (tab.lastError != null) it.put("error", tab.lastError) }
    }

    private fun stripSlash(u: String) = u.trimEnd('/')

    /** Waits until [tab] has finished a navigation that started after [afterSeq] (null: any navigation in progress). */
    private suspend fun settle(tab: BrowserTab, wv: WebView, state: String, timeoutMs: Long, afterSeq: Int?, requested: String?, startedAt: Long): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val navigated = afterSeq == null || tab.navSeq > afterSeq
            var ready = navigated && !tab.loading
            if (!ready && navigated && state == "domcontentloaded") {
                ready = callAsync(wv, "return document.readyState", 2_000).optString("result") != "loading"
            }
            if (ready && state == "networkidle") ready = System.currentTimeMillis() - app.lastRequestAt > 500
            val now = System.currentTimeMillis()
            if (ready) { if (stableSince == 0L) stableSince = now else if (now - stableSince >= 120) return report(tab, requested, state, startedAt) } else stableSince = 0L
            delay(40)
        }
        return fail("timed out after ${timeoutMs}ms waiting for the page ($state): ${tab.url}")
    }

    override fun navigate(url: String, wait: Boolean, state: String, timeoutMs: Long): JSONObject = runBlocking {
        val (tab, wv) = front() ?: return@runBlocking fail("no page is open")
        val before = tab.navSeq
        val target = normalizeUrl(url)
        val started = System.currentTimeMillis()
        withContext(Dispatchers.Main) { tab.lastError = null; tab.httpStatus = 0; wv.loadUrl(target) }
        if (!wait) JSONObject().put("ok", true) else settle(tab, wv, state, timeoutMs, before, target, started)
    }

    override fun waitNav(state: String, timeoutMs: Long): JSONObject = runBlocking {
        val (tab, wv) = front() ?: return@runBlocking fail("no page is open")
        val started = System.currentTimeMillis()
        if (tab.loading) return@runBlocking settle(tab, wv, state, timeoutMs, null, null, started)
        // Nothing loading right now: give a navigation the caller has just triggered (a click) a moment to begin.
        val seq = tab.navSeq
        val grace = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < grace && tab.navSeq == seq && !tab.loading) delay(40)
        if (tab.navSeq == seq && !tab.loading) report(tab, null, state, started, navigated = false)
        else settle(tab, wv, state, timeoutMs, seq, null, started)
    }

    /** Used when a tab is opened by the API and the caller asked to wait for it. */
    fun awaitLoad(tab: BrowserTab, wv: WebView, timeoutMs: Long) {
        runBlocking { settle(tab, wv, "load", timeoutMs, 0, null, System.currentTimeMillis()) }
    }

    // ------------------------------------------------------------------------------------------------- screenshots

    private class ShotError(message: String) : Exception(message)

    /** WebView.getScale is a WebView method, so like every other one it may only run on the main thread. */
    @Suppress("DEPRECATION")
    private suspend fun scaleOf(wv: WebView): Float = withContext(Dispatchers.Main) { wv.scale.takeIf { it > 0f } ?: wv.resources.displayMetrics.density }

    private suspend fun drawViewport(wv: WebView): Bitmap = withContext(Dispatchers.Main) {
        Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888).also { wv.draw(Canvas(it)) }
    }

    override fun screenshot(selector: String?, fullPage: Boolean, jpeg: Boolean): Pair<ByteArray?, JSONObject?> = runBlocking {
        val (_, wv) = front() ?: return@runBlocking null to fail("no page is open")
        val onScreen = withContext(Dispatchers.Main) { wv.width > 0 && wv.height > 0 && wv.isAttachedToWindow && wv.isShown }
        if (!onScreen) return@runBlocking null to fail("the page is not on screen: open AlpBrowser's Browser screen and try again")
        try {
            val bmp = when {
                selector != null -> shotElement(wv, selector)
                fullPage -> shotFull(wv)
                else -> drawViewport(wv)
            }
            val out = java.io.ByteArrayOutputStream()
            if (jpeg) bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) else bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray() to null
        } catch (e: ShotError) {
            null to fail(e.message ?: "screenshot failed")
        } catch (e: OutOfMemoryError) {
            null to fail("not enough memory for this screenshot; try without --full-page")
        }
    }

    private suspend fun shotElement(wv: WebView, selector: String): Bitmap {
        val r = callAsync(
            wv,
            "const el=document.querySelector(" + JSONObject.quote(selector) + ");if(!el)throw new Error('no element matches '+" + JSONObject.quote(selector) + ");" +
                "el.scrollIntoView({block:'center',inline:'center'});await new Promise(r=>setTimeout(r,150));const b=el.getBoundingClientRect();return{x:b.left,y:b.top,w:b.width,h:b.height};",
            10_000,
        )
        if (!r.optBoolean("ok")) throw ShotError(r.optString("error"))
        delay(120)
        val full = drawViewport(wv)
        val s = scaleOf(wv)
        val x = (r.getDouble("x") * s).toInt().coerceIn(0, full.width - 1)
        val y = (r.getDouble("y") * s).toInt().coerceIn(0, full.height - 1)
        val w = (r.getDouble("w") * s).toInt().coerceAtMost(full.width - x)
        val h = (r.getDouble("h") * s).toInt().coerceAtMost(full.height - y)
        if (w <= 0 || h <= 0) throw ShotError("the element has no visible size: $selector")
        return Bitmap.createBitmap(full, x, y, w, h)
    }

    private suspend fun shotFull(wv: WebView): Bitmap {
        val m = callAsync(wv, "return{h:Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0),vh:window.innerHeight,y:window.scrollY};", 10_000)
        if (!m.optBoolean("ok")) throw ShotError(m.optString("error"))
        val s = scaleOf(wv)
        val docCss = m.getDouble("h")
        val docPx = (docCss * s).toInt().coerceAtLeast(wv.height)
        if (docPx > 30_000 || wv.width.toLong() * docPx > 60_000_000L) throw ShotError("the page is too tall to capture whole (${docCss.toInt()} px); use --selector")
        val out = Bitmap.createBitmap(wv.width, docPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val step = (m.getDouble("vh") * 0.9).coerceAtLeast(100.0)
        var y = 0.0
        var steps = 0
        while (y < docCss && steps < 40) {
            val at = callAsync(wv, "window.scrollTo(0,$y);await new Promise(r=>setTimeout(r,160));return window.scrollY;", 10_000)
            val actual = at.optDouble("result", y)
            val part = drawViewport(wv)
            canvas.drawBitmap(part, 0f, (actual * s).toFloat(), null)
            part.recycle()
            y += step; steps++
        }
        callAsync(wv, "window.scrollTo(0," + m.getDouble("y") + ");return 0;", 5_000)
        return out
    }

    // ------------------------------------------------------------------------------------------------- cookies

    override fun cookies(domain: String?): JSONObject {
        val cm = CookieManager.getInstance()
        val hosts = if (domain != null) listOf(domain.removePrefix(".")) else {
            val fromTraffic = app.transactionStore.transactions.value.map { it.host }
            val fromTabs = app.browserWebViewHolder.tabs.mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }
            (fromTraffic + fromTabs).distinct().take(200)
        }
        val arr = org.json.JSONArray()
        val seen = HashSet<String>()
        for (h in hosts) {
            val raw = cm.getCookie("https://$h") ?: continue
            raw.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { c ->
                val name = c.substringBefore('='); val value = c.substringAfter('=')
                if (seen.add("$name=$value@$h")) arr.put(JSONObject().put("domain", h).put("name", name).put("value", value))
            }
        }
        val plain = JSONObject().put("ok", true).put("cookies", arr)
        // With Remote DevTools on, the browser itself can list every attribute (path, expiry, HttpOnly, Secure, SameSite).
        if (app.devtoolsEnabled()) {
            try {
                val full = app.cdpClient.cookies(domain)
                return JSONObject().put("ok", true).put("attributes", true).put("cookies", full)
            } catch (e: Exception) {
                return plain.put("note", "DevTools did not list the attributes (${e.message}); only name and value are shown. Open a page and retry, or run `browserctl cdp` again.")
            }
        }
        return plain.put("note", "Name and value only: turn on Remote DevTools (`browserctl cdp`) to also see path, expiry, HttpOnly, Secure and SameSite. Without --domain, the hosts seen in History and open tabs are checked.")
    }

    override fun setCookie(cookie: CookieSpec): JSONObject {
        val host = cookie.domain?.removePrefix(".") ?: app.browserWebViewHolder.active?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            ?: return fail("no domain given and no page is open")
        val attrs = CookieHeader.build(cookie).getOrElse { return fail(it.message ?: "invalid cookie") }
        val cm = CookieManager.getInstance()
        // A Secure cookie needs an https URL to be accepted; the rest go to the scheme of the open page, https by default.
        cm.setCookie("https://$host", attrs)
        cm.flush()
        return JSONObject().put("ok", true).put("domain", host).put("name", cookie.name)
    }
}
