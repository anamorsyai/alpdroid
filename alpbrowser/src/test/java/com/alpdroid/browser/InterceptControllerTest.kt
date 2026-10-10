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
}
