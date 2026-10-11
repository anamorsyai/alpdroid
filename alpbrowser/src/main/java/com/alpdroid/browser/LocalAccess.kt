package com.alpdroid.browser

/**
 * "No token needed on this phone": while open local access is on, a request to the control API or the DevTools
 * forwarder is accepted without the token, but only if it plainly comes from a program on the phone and not from a web
 * page. Web pages (including DNS-rebinding ones) are kept out by two things a script cannot fake: browsers always add
 * `Origin` / `Sec-Fetch-*` headers to requests made for a page, and a rebound request carries the attacker's name in
 * `Host`. Command-line clients (browserctl, curl, Playwright, puppeteer) send neither.
 */
object LocalAccess {
    /** [headers] has lower-cased names. */
    fun allowed(headers: Map<String, String>): Boolean {
        if (headers.keys.any { it == "origin" || it.startsWith("sec-fetch-") }) return false
        val raw = headers["host"]?.lowercase() ?: return false
        val host = if (raw.startsWith("[")) raw.substringBefore(']') + "]" else raw.substringBefore(':')
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
    }
}
