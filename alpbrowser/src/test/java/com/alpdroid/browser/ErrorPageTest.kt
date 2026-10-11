package com.alpdroid.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPageTest {
    @Test fun `the page shows the address and the reason, escaped`() {
        val h = ErrorPage.html("https://a.test/?q=<script>alert(1)</script>&x=\"y\"", "net::ERR_EMPTY_RESPONSE", captureOn = false)
        assertTrue(h.contains("net::ERR_EMPTY_RESPONSE"))
        assertTrue(h.contains("&lt;script&gt;alert(1)&lt;/script&gt;&amp;x=&quot;y&quot;"))
        assertFalse(h.contains("<script>alert"))
    }

    @Test fun `it paints its own dark background with light text and mentions Capture only when it is on`() {
        val on = ErrorPage.html("https://a.test/", "x", captureOn = true)
        assertTrue(on.contains("background:#0f1012") && on.contains("color:#e9e9ee") && on.contains("Capture traffic"))
        assertFalse(ErrorPage.html("https://a.test/", "x", captureOn = false).contains("Capture traffic"))
    }
}
