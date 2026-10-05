package com.alpdroid.app

import java.io.File

/**
 * Finds the phone's low-power ("efficiency") cores so terminal sessions can optionally be pinned to
 * them. A program that spins a core while idle (some TUIs do) then costs a fraction of the heat and
 * battery it would on a big core. Pure parsing lives in [efficiencyMask] so it is unit-testable.
 */
object CpuTopology {
    /** Hex affinity mask of the slowest cores, or null when the cores are all alike / unknown (then
     *  pinning would only slow things down for no gain). [maxFreqKhz] is indexed by cpu number; a
     *  value <= 0 means unreadable (offline core or no cpufreq) and is ignored. */
    fun efficiencyMask(maxFreqKhz: List<Long>): String? {
        val known = maxFreqKhz.withIndex().filter { it.value > 0 }
        if (known.size < 2 || maxFreqKhz.size > 63) return null
        val slowest = known.minOf { it.value }
        if (known.all { it.value == slowest }) return null
        var mask = 0L
        for ((cpu, freq) in known) if (freq == slowest) mask = mask or (1L shl cpu)
        return java.lang.Long.toHexString(mask)
    }

    /** Reads the per-core maximum frequencies from sysfs; null when unreadable. */
    fun readEfficiencyMask(): String? = runCatching {
        val base = File("/sys/devices/system/cpu")
        val freqs = (0 until Runtime.getRuntime().availableProcessors().coerceAtMost(63)).map { cpu ->
            File(base, "cpu$cpu/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull() ?: -1L
        }
        efficiencyMask(freqs)
    }.getOrNull()
}
