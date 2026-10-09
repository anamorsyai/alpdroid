package com.alpdroid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdaterDigestTest {
    private val hex = "a".repeat(64)

    @Test fun readsAGithubSha256Digest() {
        assertEquals(hex, AppUpdater.sha256Of("sha256:$hex"))
        assertEquals(hex, AppUpdater.sha256Of("sha256:" + hex.uppercase()))
    }

    @Test fun ignoresAnythingElse() {
        assertEquals("", AppUpdater.sha256Of(""))
        assertEquals("", AppUpdater.sha256Of("md5:$hex"))
        assertEquals("", AppUpdater.sha256Of("sha256:abc"))
        assertEquals("", AppUpdater.sha256Of("sha256:" + "g".repeat(64)))
    }
}
