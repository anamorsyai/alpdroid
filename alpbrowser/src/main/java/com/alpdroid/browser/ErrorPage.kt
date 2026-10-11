package com.alpdroid.browser

/** The page shown when a main-frame load fails: readable on the dark app (the system's own error page is black text on a transparent canvas). */
object ErrorPage {
    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c) }
    }

    /** [description] is the system's text (for example `net::ERR_EMPTY_RESPONSE`); [captureOn] adds the hint about the proxy. */
    fun html(url: String, description: String, captureOn: Boolean): String {
        val u = escape(url); val d = escape(description)
        val hint = if (captureOn) "<p class=h>Capture is on, so this page goes through AlpBrowser's proxy. If it keeps failing, turn <b>Capture traffic</b> off in the &#8942; menu and reload to see whether the proxy is the cause.</p>" else ""
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Can't open this page</title><style>
body{margin:0;padding:28px 20px;background:#0f1012;color:#e9e9ee;font:16px/1.5 -apple-system,Roboto,sans-serif}
h1{font-size:22px;margin:0 0 14px;color:#fff}
.u{word-break:break-all;color:#ff7a45;margin:0 0 14px}
.e{font:14px monospace;color:#b9b9c4;margin:0 0 20px}
.h{color:#b9b9c4;font-size:14px}
a.b{display:inline-block;background:#ff7a45;color:#0f1012;text-decoration:none;font-weight:600;padding:10px 18px;border-radius:10px}
</style></head><body><h1>Can't open this page</h1><p class=u>$u</p><p class=e>$d</p><a class=b href="$u">Reload</a>$hint</body></html>"""
    }
}
