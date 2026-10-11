package com.alpdroid.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePickTest {
    private fun release(tag: String, apk: Boolean = true, draft: Boolean = false, pre: Boolean = false): JSONObject =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", pre).put(
            "assets",
            JSONArray().apply { if (apk) put(JSONObject().put("name", "$tag.apk")) else put(JSONObject().put("name", "notes.txt")) },
        )

    private fun page(vararg r: JSONObject) = JSONArray().apply { r.forEach { put(it) } }

    private val appBelongs = { tag: String -> !BrowserInstall.isBrowserTag(tag) }
    private val browserBelongs = { tag: String -> BrowserInstall.isBrowserTag(tag) }
    private fun appNewer(current: String) = { tag: String -> AppUpdater.isNewer(tag, current) }
    private fun browserNewer(installed: String?) = { tag: String ->
        installed == null || AppUpdater.isNewer(BrowserInstall.versionFromTag(tag), installed)
    }

    @Test fun newerReleaseInChannelIsFound() {
        val p = AppUpdater.pickFromPage(page(release("v1.7.75"), release("v1.7.74")), appBelongs, appNewer("1.7.74"))
        assertTrue(p is AppUpdater.Pick.Found)
        assertEquals("v1.7.75", (p as AppUpdater.Pick.Found).release.getString("tag_name"))
    }

    @Test fun alreadyCurrentOffersNothing() {
        val p = AppUpdater.pickFromPage(page(release("v1.7.74"), release("v1.7.73")), appBelongs, appNewer("1.7.74"))
        assertSame(AppUpdater.Pick.Continue, p)
    }

    @Test fun newestVersionIsChosenEvenWhenTheListIsNotInVersionOrder() {
        // GitHub listed alpbrowser-v0.6.10 below v0.6.2 (it sorts tags as text). The installed 0.6.9 must still see 0.6.10.
        val p = AppUpdater.pickFromPage(
            page(release("alpbrowser-v0.6.9"), release("alpbrowser-v0.6.8"), release("alpbrowser-v0.6.2"), release("alpbrowser-v0.6.10"), release("alpbrowser-v0.6.1")),
            browserBelongs, browserNewer("0.6.9"),
        )
        assertEquals("alpbrowser-v0.6.10", (p as AppUpdater.Pick.Found).release.getString("tag_name"))
    }

    @Test fun theHighestOfSeveralNewerReleasesWins() {
        val p = AppUpdater.pickFromPage(page(release("v1.7.76"), release("v1.7.100"), release("v1.7.99")), appBelongs, appNewer("1.7.74"))
        assertEquals("v1.7.100", (p as AppUpdater.Pick.Found).release.getString("tag_name"))
    }

    @Test fun otherChannelsReleasesAreSkippedAndSearchContinues() {
        // A page that is all AlpDroid releases says nothing about the browser channel: keep paging.
        val p = AppUpdater.pickFromPage(page(release("v1.7.74"), release("v1.7.73")), browserBelongs, browserNewer("0.2.2"))
        assertSame(AppUpdater.Pick.Continue, p)
    }

    @Test fun browserReleaseFoundAmongAppReleases() {
        val p = AppUpdater.pickFromPage(
            page(release("v1.7.74"), release("alpbrowser-v0.3.0"), release("alpbrowser-v0.2.2")),
            browserBelongs, browserNewer("0.2.2"),
        )
        assertEquals("alpbrowser-v0.3.0", (p as AppUpdater.Pick.Found).release.getString("tag_name"))
    }

    @Test fun browserNotInstalledOffersTheNewestRelease() {
        val p = AppUpdater.pickFromPage(page(release("alpbrowser-v0.2.2"), release("alpbrowser-v0.2.1")), browserBelongs, browserNewer(null))
        assertEquals("alpbrowser-v0.2.2", (p as AppUpdater.Pick.Found).release.getString("tag_name"))
    }

    @Test fun appUpdateNeverOffersABrowserRelease() {
        val p = AppUpdater.pickFromPage(page(release("alpbrowser-v9.9.9")), appBelongs, appNewer("1.7.74"))
        assertSame(AppUpdater.Pick.Continue, p)
    }

    @Test fun draftsAndPrereleasesAreIgnored() {
        val p = AppUpdater.pickFromPage(
            page(release("v1.8.0", draft = true), release("v1.7.99", pre = true), release("v1.7.75")),
            appBelongs, appNewer("1.7.74"),
        )
        assertEquals("v1.7.75", (p as AppUpdater.Pick.Found).release.getString("tag_name"))
    }

    @Test fun newerReleaseWithoutAnApkIsPassedOver() {
        val p = AppUpdater.pickFromPage(
            page(release("v1.7.76", apk = false), release("v1.7.75")),
            appBelongs, appNewer("1.7.74"),
        )
        assertEquals("v1.7.75", (p as AppUpdater.Pick.Found).release.getString("tag_name"))
    }

    @Test fun emptyPageContinues() {
        assertSame(AppUpdater.Pick.Continue, AppUpdater.pickFromPage(JSONArray(), appBelongs, appNewer("1.0.0")))
    }
}
