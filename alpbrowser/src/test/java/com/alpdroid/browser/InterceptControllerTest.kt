package com.alpdroid.browser

import com.alpdroid.proxy.InterceptedRequest
import com.alpdroid.proxy.RequestAction
import com.alpdroid.proxy.ResponseAction
import com.alpdroid.proxy.TrafficSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterceptControllerTest {
    private fun req(host: String = "example.com", method: String = "GET") =
        InterceptedRequest("tx1", TrafficSource.BROWSER, method, "https", host, 443, "/", emptyList(), null)

    @Test fun `forwards everything while disabled`() = runTest {
        val c = InterceptController()
        assertTrue(c.interceptor.onRequest(req()) is RequestAction.Forward)
        assertTrue(c.pending().isEmpty())
    }

    @Test fun `a held request is parked until it is resolved`() = runTest {
        val c = InterceptController()
        c.enabled = true
        val result = async { c.interceptor.onRequest(req()) }
        // Let it park.
        var held = c.pending()
        var tries = 0
        while (held.isEmpty() && tries++ < 50) { delay(5); held = c.pending() }
        assertEquals(1, held.size)
        assertEquals("request", held[0].kind)
        assertTrue(c.resolveRequest(held[0].holdId, RequestAction.Replace(method = "POST")))
        val action = result.await()
        assertTrue(action is RequestAction.Replace && action.method == "POST")
        assertTrue(c.pending().isEmpty())
    }

    @Test fun `scope filters by host`() = runTest {
        val c = InterceptController()
        c.enabled = true
        c.hostFilter = "target.test"
        // Off-scope: forwarded without parking.
        assertTrue(c.interceptor.onRequest(req(host = "other.com")) is RequestAction.Forward)
        assertTrue(c.pending().isEmpty())
    }

    @Test fun `releaseAll forwards everything parked`() = runTest {
        val c = InterceptController()
        c.enabled = true
        val r = async { c.interceptor.onRequest(req()) }
        var tries = 0
        while (c.pending().isEmpty() && tries++ < 50) delay(5)
        c.releaseAll()
        assertTrue(r.await() is RequestAction.Forward)
    }

    @Test fun `resolving an unknown hold returns false`() {
        val c = InterceptController()
        assertFalse(c.resolveRequest("nope", RequestAction.Forward))
        assertFalse(c.resolveResponse("nope", ResponseAction.Forward))
    }

    @Test fun `pendingFlow follows parked messages`() = runTest {
        val c = InterceptController()
        c.enabled = true
        val r = async { c.interceptor.onRequest(req()) }
        var tries = 0
        while (c.pendingFlow.value.isEmpty() && tries++ < 50) delay(5)
        assertEquals(1, c.pendingFlow.value.size)
        c.releaseAll()
        r.await()
        assertTrue(c.pendingFlow.value.isEmpty())
    }

    private fun res(id: String = "tx1", ct: String? = null) = com.alpdroid.proxy.InterceptedResponse(
        id, TrafficSource.BROWSER, "example.com", 443, 200, ct?.let { listOf("Content-Type" to it) } ?: emptyList(), null,
    )

    @Test fun `responses are only held when asked for`() = runTest {
        val c = InterceptController()
        c.enabled = true
        assertTrue(c.interceptor.onResponse(res()) is ResponseAction.Forward)
        assertTrue(c.pending().isEmpty())
        c.interceptResponses = true
        val held = async { c.interceptor.onResponse(res()) }
        var tries = 0
        while (c.pending().isEmpty() && tries++ < 50) delay(5)
        assertEquals("response", c.pending().single().kind)
        c.releaseAll()
        assertTrue(held.await() is ResponseAction.Forward)
    }

    @Test fun `images stylesheets and scripts are skipped unless asked for`() = runTest {
        val c = InterceptController()
        c.enabled = true
        c.interceptResponses = true
        fun r(target: String) = InterceptedRequest("t", TrafficSource.BROWSER, "GET", "https", "example.com", 443, target, emptyList(), null)
        assertTrue(c.interceptor.onRequest(r("/a/logo.png?x=1")) is RequestAction.Forward)
        assertTrue(c.interceptor.onRequest(r("/static/app.js")) is RequestAction.Forward)
        assertTrue(c.interceptor.onResponse(res("t")) is ResponseAction.Forward) // belongs to a skipped request
        assertTrue(c.interceptor.onResponse(res("other", "image/png")) is ResponseAction.Forward)
        assertTrue(c.pending().isEmpty())
        c.skipStatic = false
        val held = async { c.interceptor.onRequest(r("/a/logo.png")) }
        var tries = 0
        while (c.pending().isEmpty() && tries++ < 50) delay(5)
        assertEquals(1, c.pending().size)
        c.releaseAll(); held.await()
    }

    @Test fun `switching intercept tells the app so it can route through the proxy`() {
        val c = InterceptController()
        val seen = mutableListOf<Boolean>()
        c.onEnabledChange = { seen.add(it) }
        c.enabled = true; c.enabled = true; c.enabled = false
        assertEquals(listOf(true, false), seen)
    }
}
