package com.alpdroid.app

import com.alpdroid.app.LoadBalancer.Input
import com.alpdroid.app.LoadBalancer.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadBalancerTest {
    private fun input(active: Boolean = false, visible: Boolean = true, hot: Boolean = false, cpu: Double) = Input(active, visible, hot, cpu)

    @Test fun foregroundTabIsNeverParkedWhenCool() {
        val s = LoadBalancer.State()
        repeat(10) { assertNull(LoadBalancer.step(s, input(active = true, cpu = 300.0))) }
        assertEquals(Placement.ALL, s.placement)
    }

    @Test fun busyBackgroundTabIsParkedAfterTwoSamples() {
        val s = LoadBalancer.State()
        assertNull(LoadBalancer.step(s, input(cpu = 120.0)))
        assertEquals(Placement.EFFICIENCY, LoadBalancer.step(s, input(cpu = 120.0)))
    }

    @Test fun aSingleSpikeDoesNotMoveAnything() {
        val s = LoadBalancer.State()
        assertNull(LoadBalancer.step(s, input(cpu = 150.0)))
        assertNull(LoadBalancer.step(s, input(cpu = 5.0)))
        assertNull(LoadBalancer.step(s, input(cpu = 150.0)))
        assertEquals(Placement.ALL, s.placement)
    }

    @Test fun idleBackgroundTabStaysOnAllCores() {
        val s = LoadBalancer.State()
        repeat(6) { assertNull(LoadBalancer.step(s, input(cpu = 3.0))) }
    }

    @Test fun everythingBusyIsParkedWhileTheAppIsHidden() {
        val s = LoadBalancer.State()
        LoadBalancer.step(s, input(active = true, visible = false, cpu = 55.0))
        assertEquals(Placement.EFFICIENCY, LoadBalancer.step(s, input(active = true, visible = false, cpu = 55.0)))
    }

    @Test fun returningToTheTabGivesAllCoresBackAtOnce() {
        val s = LoadBalancer.State()
        LoadBalancer.step(s, input(cpu = 120.0)); LoadBalancer.step(s, input(cpu = 120.0))
        assertEquals(Placement.EFFICIENCY, s.placement)
        assertEquals(Placement.ALL, LoadBalancer.step(s, input(active = true, cpu = 120.0)))
    }

    @Test fun parkedSessionThatCalmsDownGetsCoresBack() {
        val s = LoadBalancer.State()
        LoadBalancer.step(s, input(cpu = 120.0)); LoadBalancer.step(s, input(cpu = 120.0))
        assertNull(LoadBalancer.step(s, input(cpu = 5.0)))
        assertEquals(Placement.ALL, LoadBalancer.step(s, input(cpu = 5.0)))
    }

    @Test fun parkedBusySessionStaysParked() {
        val s = LoadBalancer.State()
        LoadBalancer.step(s, input(cpu = 120.0)); LoadBalancer.step(s, input(cpu = 120.0))
        repeat(5) { assertNull(LoadBalancer.step(s, input(cpu = 80.0))) }
        assertEquals(Placement.EFFICIENCY, s.placement)
    }

    @Test fun hotPhoneParksEvenTheForegroundTab() {
        val s = LoadBalancer.State()
        LoadBalancer.step(s, input(active = true, hot = true, cpu = 90.0))
        assertEquals(Placement.EFFICIENCY, LoadBalancer.step(s, input(active = true, hot = true, cpu = 90.0)))
        // and it is not given back immediately while still hot, even though the user is on it
        assertNull(LoadBalancer.step(s, input(active = true, hot = true, cpu = 90.0)))
    }
}
