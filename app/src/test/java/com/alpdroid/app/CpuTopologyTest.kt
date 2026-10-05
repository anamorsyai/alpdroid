package com.alpdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuTopologyTest {
    @Test fun bigLittleSelectsSlowestCluster() {
        // 4 little @1.8GHz (cpu0-3), 3 mid @2.4, 1 prime @2.8
        assertEquals("f", CpuTopology.efficiencyMask(listOf(1800000, 1800000, 1800000, 1800000, 2400000, 2400000, 2400000, 2800000)))
    }
    @Test fun uniformCoresGiveNull() {
        assertNull(CpuTopology.efficiencyMask(listOf(2000000, 2000000, 2000000, 2000000)))
    }
    @Test fun unreadableOrSingleCoreGivesNull() {
        assertNull(CpuTopology.efficiencyMask(listOf(-1, -1, -1)))
        assertNull(CpuTopology.efficiencyMask(listOf(2000000)))
    }
    @Test fun offlineCoresAreIgnored() {
        assertEquals("c", CpuTopology.efficiencyMask(listOf(-1, -1, 1500000, 1500000, 2500000)))
    }
}
