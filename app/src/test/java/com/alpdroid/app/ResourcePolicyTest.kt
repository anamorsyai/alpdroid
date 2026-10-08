package com.alpdroid.app

import com.alpdroid.app.ResourcePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePolicyTest {
    @Test fun normalWhenCoolAndCharged() = assertEquals(Mode.NORMAL, ResourcePolicy.mode(0, false, 80, false))

    @Test fun severeHeatMeansCool() = assertEquals(Mode.COOL, ResourcePolicy.mode(3, false, 80, true))

    @Test fun criticalHeatMeansCool() = assertEquals(Mode.COOL, ResourcePolicy.mode(5, false, 80, true))

    @Test fun moderateHeatMeansEco() = assertEquals(Mode.ECO, ResourcePolicy.mode(2, false, 80, true))

    @Test fun batterySaverMeansEco() = assertEquals(Mode.ECO, ResourcePolicy.mode(0, true, 80, false))

    @Test fun lowBatteryUnpluggedMeansEco() = assertEquals(Mode.ECO, ResourcePolicy.mode(0, false, 10, false))

    @Test fun lowBatteryWhileChargingIsFine() = assertEquals(Mode.NORMAL, ResourcePolicy.mode(0, false, 10, true))

    @Test fun unknownBatteryIsFine() = assertEquals(Mode.NORMAL, ResourcePolicy.mode(0, false, -1, false))

    @Test fun framesGetSlowerAsPhoneGetsWorse() {
        assertTrue(Mode.NORMAL.frameIntervalMs < Mode.ECO.frameIntervalMs)
        assertTrue(Mode.ECO.frameIntervalMs < Mode.COOL.frameIntervalMs)
    }

    @Test fun cpuPercentFromJiffies() {
        // 100 jiffies/s: 50 jiffies over 1000 ms is half a core.
        assertEquals(50.0, ResourcePolicy.cpuPercent(50, 1000), 0.001)
        assertEquals(200.0, ResourcePolicy.cpuPercent(200, 1000), 0.001)
        assertEquals(0.0, ResourcePolicy.cpuPercent(10, 0), 0.0)
        assertEquals(0.0, ResourcePolicy.cpuPercent(-5, 1000), 0.0)
    }

    @Test fun parsesProcStatWithAwkwardCommandName() {
        val line = "1234 (we ird) name) S 77 1234 1234 34816 1234 4194560 100 0 0 0 150 25 0 0 20 0 1 0 5555 1000 200 18446744073709551615"
        val s = ResourcePolicy.parseStat(line)!!
        assertEquals(77, s.ppid)
        assertEquals(175L, s.cpuJiffies)
    }

    @Test fun rejectsGarbageProcStat() {
        assertNull(ResourcePolicy.parseStat("nonsense"))
        assertNull(ResourcePolicy.parseStat("1 (x) S 2"))
    }

    @Test fun parsesStatmRss() {
        assertEquals(300L * 4096, ResourcePolicy.parseStatmRssBytes("5000 300 120 10 0 200 0"))
        assertEquals(0L, ResourcePolicy.parseStatmRssBytes(""))
    }

    @Test fun findsAllDescendants() {
        val parentOf = mapOf(10 to 1, 11 to 10, 12 to 10, 13 to 11, 99 to 1)
        assertEquals(setOf(11, 12, 13), ResourcePolicy.descendants(10, parentOf))
        assertTrue(ResourcePolicy.descendants(13, parentOf).isEmpty())
    }

    @Test fun descendantsTerminateOnCyclesInTheMap() {
        // pid reuse can briefly make the parent map cyclic; the walk must still end.
        val parentOf = mapOf(1 to 2, 2 to 1, 3 to 1)
        assertTrue(ResourcePolicy.descendants(1, parentOf).containsAll(setOf(2, 3)))
    }

    @Test fun runawayNeedsSustainedBusyBridgeWithIdleGuest() {
        val d = ResourcePolicy.RunawayDetector()
        assertFalse(d.update(90.0, 1.0))
        assertFalse(d.update(90.0, 1.0))
        assertTrue(d.update(90.0, 1.0))
    }

    @Test fun aBusyGuestIsNotARunaway() {
        val d = ResourcePolicy.RunawayDetector()
        repeat(10) { assertFalse(d.update(90.0, 80.0)) }
    }

    @Test fun aShortBurstResetsTheStreak() {
        val d = ResourcePolicy.RunawayDetector()
        d.update(90.0, 1.0); d.update(90.0, 1.0)
        assertFalse(d.update(5.0, 1.0)) // calmed down
        assertFalse(d.update(90.0, 1.0))
        assertFalse(d.update(90.0, 1.0))
        assertTrue(d.update(90.0, 1.0))
    }

    @Test fun parsesMemAvailable() {
        assertEquals(1024L, ResourcePolicy.parseMemAvailableMb("MemTotal:  8000000 kB\nMemAvailable:    1048576 kB\n"))
        assertEquals(-1L, ResourcePolicy.parseMemAvailableMb("MemTotal: 1 kB\n"))
    }

    @Test fun killExplanationNamesTheLikelyKiller() {
        assertTrue(ResourcePolicy.explainKill(12, 33, 2000).contains("child-process limit"))
        assertTrue(ResourcePolicy.explainKill(5, 6, 150).contains("low memory"))
        assertTrue(ResourcePolicy.explainKill(5, 6, 2000).contains("battery manager"))
        assertTrue(ResourcePolicy.explainKill(0, 0, -1).startsWith("killed (SIGKILL)"))
    }
}
