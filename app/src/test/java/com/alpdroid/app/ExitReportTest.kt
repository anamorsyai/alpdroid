package com.alpdroid.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReportTest {
    @Test fun userDrivenExitsAreNotReported() {
        for (reason in listOf(1, 10, 11, 8, 15, 16)) assertNull(ExitReport.describe(reason, 400, 0, null))
    }

    @Test fun lowMemoryExplainsAndAdvises() {
        val text = ExitReport.describe(3, 125, 700 * 1024, null)!!
        assertTrue(text, text.contains("low on memory"))
        assertTrue(text, text.contains("keep-alive service was running"))
        assertTrue(text, text.contains("about 700 MB"))
        assertTrue(text, text.contains("Terminal memory"))
    }

    @Test fun killSignalWhileCachedPointsAtBatterySettings() {
        val text = ExitReport.describe(2, 400, 0, "kill 9")!!
        assertTrue(text, text.contains("kill signal"))
        assertTrue(text, text.contains("cached in the background"))
        assertTrue(text, text.contains("Android says: kill 9"))
        assertTrue(text, text.contains("battery"))
    }

    @Test fun crashAsksForAReport() {
        assertTrue(ExitReport.describe(4, 100, 0, null)!!.contains("crashed"))
    }
}
