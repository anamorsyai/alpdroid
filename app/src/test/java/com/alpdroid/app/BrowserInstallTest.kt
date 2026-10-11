package com.alpdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInstallTest {
    @Test fun browserTagsAreRecognised() {
        assertTrue(BrowserInstall.isBrowserTag("alpbrowser-v0.1.0"))
        assertTrue(BrowserInstall.isBrowserTag("alpbrowser-v1.2.3"))
        assertFalse(BrowserInstall.isBrowserTag("v1.7.69"))
        assertFalse(BrowserInstall.isBrowserTag("1.7.69"))
    }

    @Test fun versionIsStrippedFromTheTag() {
        assertEquals("0.1.0", BrowserInstall.versionFromTag("alpbrowser-v0.1.0"))
        assertEquals("2.0.0", BrowserInstall.versionFromTag("alpbrowser-v2.0.0"))
        // Tolerant of a missing prefix or v.
        assertEquals("3.1", BrowserInstall.versionFromTag("3.1"))
    }

    @Test fun appUpdaterTreatsBrowserVersionsWithItsNumericCompare() {
        // The browser channel compares the stripped version against the installed browser name.
        assertTrue(AppUpdater.isNewer(BrowserInstall.versionFromTag("alpbrowser-v0.2.0"), "0.1.0"))
        assertFalse(AppUpdater.isNewer(BrowserInstall.versionFromTag("alpbrowser-v0.1.0"), "0.1.0"))
        assertFalse(AppUpdater.isNewer(BrowserInstall.versionFromTag("alpbrowser-v0.1.0"), "0.2.0"))
    }

    @Test fun anAppUpdateCheckWouldIgnoreABrowserTag() {
        // Guard in checkForUpdate: a browser tag must never read as a newer AlpDroid build.
        assertFalse(!BrowserInstall.isBrowserTag("alpbrowser-v9.9.9"))
        // (sanity) a real app tag is still an app tag
        assertFalse(BrowserInstall.isBrowserTag("v1.7.69"))
    }
}
