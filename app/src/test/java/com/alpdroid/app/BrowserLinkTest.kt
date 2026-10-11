package com.alpdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLinkTest {
    private val minute = 60_000L

    @Test fun envTextIsExactlyWhatBrowserctlParses() {
        val text = BrowserLink.envText(BrowserLink.Info("abc123", 8899, 8890))
        assertEquals("ALPBROWSER_TOKEN=abc123\nALPBROWSER_PORT=8899\nALPBROWSER_PROXY=8890\n", text)
    }

    @Test fun aMissingFileAlwaysNeedsRefresh() {
        assertTrue(BrowserLink.needsRefresh(envExists = false, envModifiedMs = 0, browserUpdatedMs = 0, nowMs = 1_000 * minute))
    }

    @Test fun aFreshFileWrittenAfterTheInstallIsLeftAlone() {
        // The common tab-start case: nothing to ask, just a couple of stat calls.
        assertFalse(BrowserLink.needsRefresh(envExists = true, envModifiedMs = 995 * minute, browserUpdatedMs = 900 * minute, nowMs = 1_000 * minute))
    }

    @Test fun aFileOlderThanTheLastBrowserUpdateIsStale() {
        // AlpBrowser was reinstalled/updated after the file was written: its token or ports may have changed.
        assertTrue(BrowserLink.needsRefresh(envExists = true, envModifiedMs = 900 * minute, browserUpdatedMs = 950 * minute, nowMs = 951 * minute))
    }

    @Test fun anOldFileIsReCheckedEvenWithoutAnUpdate() {
        // The bound control port can differ from run to run, so don't trust a file forever.
        assertTrue(BrowserLink.needsRefresh(envExists = true, envModifiedMs = 100 * minute, browserUpdatedMs = 0, nowMs = 100 * minute + 11 * minute))
        assertFalse(BrowserLink.needsRefresh(envExists = true, envModifiedMs = 100 * minute, browserUpdatedMs = 0, nowMs = 100 * minute + 9 * minute))
    }
}
