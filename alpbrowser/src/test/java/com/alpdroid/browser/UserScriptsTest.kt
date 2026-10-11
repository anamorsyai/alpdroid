package com.alpdroid.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptsTest {
    private val script = """
        // ==UserScript==
        // @name        Dark Mode
        // @namespace   test
        // @version     1.2
        // @description Makes things dark
        // @match       *://*.example.com/*
        // @exclude     https://admin.example.com/*
        // @run-at      document-start
        // @noframes
        // ==/UserScript==
        document.body.style.background = '#000';
    """.trimIndent()

    private fun tmp() = java.nio.file.Files.createTempDirectory("ext").toFile()

    @Test fun `header metadata is parsed`() {
        val m = UserScriptParser.parse(script)!!
        assertEquals("Dark Mode", m.name)
        assertEquals("1.2", m.version)
        assertEquals(listOf("*://*.example.com/*"), m.matches)
        assertEquals("document-start", m.runAt)
        assertTrue(m.noFrames)
    }

    @Test fun `match patterns follow Chrome semantics`() {
        val p = listOf("*://*.example.com/*")
        assertTrue(UrlPatterns.matches(p, emptyList(), "https://example.com/"))
        assertTrue(UrlPatterns.matches(p, emptyList(), "http://a.b.example.com/x?y=1"))
        assertFalse(UrlPatterns.matches(p, emptyList(), "https://notexample.com/"))
        assertFalse(UrlPatterns.matches(p, emptyList(), "https://example.com.evil.test/"))
        assertFalse(UrlPatterns.matches(p, listOf("https://admin.example.com/*"), "https://admin.example.com/x"))
        assertTrue(UrlPatterns.matches(listOf("<all_urls>"), emptyList(), "https://x.test/"))
        assertTrue(UrlPatterns.matches(listOf("/^https://x\\.test/.*/"), emptyList(), "https://x.test/a"))
        assertTrue(UrlPatterns.matches(emptyList(), emptyList(), "https://anything.test/"))
    }

    @Test fun `generated regexes use only syntax JavaScript understands`() {
        val re = UrlPatterns.toRegex("*://*.example.com/a?b=1")!!
        assertFalse(re, re.contains("\\Q") || re.contains("\\E"))
    }

    @Test fun `installed scripts survive a new store instance and a restart`() {
        val dir = tmp()
        val first = ExtensionStore(dir)
        val r = first.install(script, "https://s.test/dark.user.js") as ExtensionStore.InstallResult.Installed
        assertFalse(r.replaced)
        first.setEnabled(r.script.id, false)

        val reopened = ExtensionStore(File(dir.path)).list()
        assertEquals(1, reopened.size)
        assertEquals("Dark Mode", reopened[0].name)
        assertFalse(reopened[0].enabled)
        assertEquals("https://s.test/dark.user.js", reopened[0].sourceUrl)
    }

    @Test fun `reinstalling updates in place and keeps the enabled state`() {
        val store = ExtensionStore(tmp())
        val id = (store.install(script) as ExtensionStore.InstallResult.Installed).script.id
        store.setEnabled(id, false)
        val again = store.install(script.replace("1.2", "1.3")) as ExtensionStore.InstallResult.Installed
        assertTrue(again.replaced)
        assertEquals(id, again.script.id)
        assertEquals("1.3", again.script.version)
        assertFalse(again.script.enabled)
        assertEquals(1, store.list().size)
    }

    @Test fun `remove deletes the script`() {
        val store = ExtensionStore(tmp())
        val id = (store.install(script) as ExtensionStore.InstallResult.Installed).script.id
        assertTrue(store.remove(id))
        assertTrue(store.list().isEmpty())
        assertFalse(store.remove("../../etc/passwd"))
    }

    @Test fun `packages and non-scripts are rejected with a reason`() {
        val store = ExtensionStore(tmp())
        assertTrue(store.install("Cr24\u0003\u0000") is ExtensionStore.InstallResult.Rejected)
        assertTrue(store.install("PK\u0003\u0004 zip") is ExtensionStore.InstallResult.Rejected)
        assertTrue(store.install("console.log(1)") is ExtensionStore.InstallResult.Rejected)
        assertTrue(store.list().isEmpty())
    }

    @Test fun `the generated wrapper embeds the script and its match rules`() {
        val s = (ExtensionStore(tmp()).install(script) as ExtensionStore.InstallResult.Installed).script
        val js = UserScriptRunner.wrap(s)
        assertNotNull(js)
        assertTrue(js.contains("document.body.style.background"))
        assertTrue(js.contains("window.top!==window"))
        assertTrue(js.contains("document-start"))
    }
}
