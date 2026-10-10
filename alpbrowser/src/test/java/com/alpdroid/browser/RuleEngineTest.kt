package com.alpdroid.browser

import com.alpdroid.proxy.InterceptedRequest
import com.alpdroid.proxy.InterceptedResponse
import com.alpdroid.proxy.TrafficSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private fun req(host: String = "target.com", headers: List<Pair<String, String>> = emptyList(), body: ByteArray? = null) =
        InterceptedRequest("t", TrafficSource.BROWSER, "GET", "https", host, 443, "/", headers, body)
    private fun res(host: String = "target.com", headers: List<Pair<String, String>> = emptyList(), body: ByteArray? = null) =
        InterceptedResponse("t", TrafficSource.BROWSER, host, 443, 200, headers, body)

    @Test fun `adds a request header when find is blank`() {
        val e = RuleEngine()
        e.add(RuleEngine.Rule("r1", RuleEngine.Part.REQ_HEADER, find = "", replace = "X-Bug: 1"))
        val edit = e.applyRequest(req())!!
        assertTrue(edit.headers!!.contains("X-Bug" to "1"))
    }

    @Test fun `rewrites a response body substring`() {
        val e = RuleEngine()
        e.add(RuleEngine.Rule("r1", RuleEngine.Part.RES_BODY, find = "admin", replace = "pwned"))
        val edit = e.applyResponse(res(body = "role=admin;ok".toByteArray()))!!
        assertEquals("role=pwned;ok", String(edit.body!!))
    }

    @Test fun `regex replace works on a request body`() {
        val e = RuleEngine()
        e.add(RuleEngine.Rule("r1", RuleEngine.Part.REQ_BODY, find = "id=\\d+", replace = "id=0", regex = true))
        val edit = e.applyRequest(req(body = "u=1&id=4567&x=2".toByteArray()))!!
        assertEquals("u=1&id=0&x=2", String(edit.body!!))
    }

    @Test fun `host scope limits a rule`() {
        val e = RuleEngine()
        e.add(RuleEngine.Rule("r1", RuleEngine.Part.REQ_HEADER, find = "", replace = "X: 1", hostContains = "only.com"))
        assertNull(e.applyRequest(req(host = "other.com")))
        assertTrue(e.applyRequest(req(host = "only.com")) != null)
    }

    @Test fun `no matching rule returns null`() {
        val e = RuleEngine()
        assertNull(e.applyRequest(req(body = "x".toByteArray())))
    }

    @Test fun `remove deletes a rule`() {
        val e = RuleEngine()
        e.add(RuleEngine.Rule("r1", RuleEngine.Part.REQ_BODY, find = "a", replace = "b"))
        assertTrue(e.remove("r1"))
        assertEquals(0, e.all().size)
    }
}
