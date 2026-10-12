package com.alpdroid.browser

import org.json.JSONObject

/**
 * What the control API needs from the live page. Implemented by the app (it touches WebViews, so it lives behind the
 * main thread); the bridge itself stays plain JVM and unit-testable. Every method returns JSON with `"ok"` first: a
 * failure is `{"ok":false,"error":...}` so the CLI can fail loudly instead of printing something success-looking.
 */
interface PageControl {
    /**
     * Runs [block] with every page command inside it aimed at tab [tab] instead of the front tab (null = the front tab).
     * This is what lets several agents work in different tabs at once without one steering the other's page.
     */
    fun <T> withTab(tab: Int?, block: () -> T): T = block()
    /** One element command in the front tab: click, fill, type, press, text, attr, exists, waitfor, html. */
    fun command(args: JSONObject): JSONObject
    /** Load [url] in the front tab; with [wait], block until it has loaded (or [timeoutMs] passes). */
    fun navigate(url: String, wait: Boolean, state: String, timeoutMs: Long): JSONObject
    /** Block until the front tab finishes loading (a navigation already running, or one that starts within a second). */
    fun waitNav(state: String, timeoutMs: Long): JSONObject
    /** Run [js] in the page; with [await], the script's value (a promise too) is awaited. */
    fun eval(js: String, await: Boolean, timeoutMs: Long): JSONObject
    /** A screenshot of the front tab as image bytes, or an error object. */
    fun screenshot(selector: String?, fullPage: Boolean, jpeg: Boolean): Pair<ByteArray?, JSONObject?>
    fun cookies(domain: String?): JSONObject
    fun setCookie(cookie: CookieSpec): JSONObject
}

/** Remote debugging (CDP) on a loopback port, gated by the control token. */
interface CdpControl {
    fun status(): JSONObject
    fun set(enabled: Boolean, preferredPort: Int?): JSONObject
}
