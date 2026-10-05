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

    /** ARM "CPU part" ids of low-power cores (Cortex-A5/A7/A32/A34/A35/A53/A55/A510/A520). */
    private val LITTLE_PARTS = setOf(0xc05, 0xc07, 0xd01, 0xd02, 0xd03, 0xd04, 0xd05, 0xd46, 0xd80)

    /** `CPU part` values from /proc/cpuinfo text, indexed by processor number (-1 when missing). */
    fun parseCpuParts(cpuinfo: String): List<Int> {
        val parts = HashMap<Int, Int>()
        var cpu = -1
        for (line in cpuinfo.lineSequence()) {
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "processor" -> cpu = value.toIntOrNull() ?: -1
                "CPU part" -> if (cpu >= 0) value.removePrefix("0x").toIntOrNull(16)?.let { parts[cpu] = it }
            }
        }
        if (parts.isEmpty()) return emptyList()
        return (0..parts.keys.max()).map { parts[it] ?: -1 }
    }

    /** Efficiency mask from known core types: only when low-power and larger cores are both present. */
    fun efficiencyMaskFromParts(parts: List<Int>): String? {
        if (parts.size > 63) return null
        val known = parts.withIndex().filter { it.value >= 0 }
        val little = known.filter { it.value in LITTLE_PARTS }
        if (little.isEmpty() || little.size == known.size) return null
        var mask = 0L
        for ((cpu, _) in little) mask = mask or (1L shl cpu)
        return java.lang.Long.toHexString(mask)
    }

    /** Detects the low-power cores: per-core max frequency, else the kernel's cpu_capacity, else the
     *  core types in /proc/cpuinfo (many phones hide cpufreq from apps). Null when none works. */
    fun readEfficiencyMask(): String? = runCatching {
        val base = File("/sys/devices/system/cpu")
        val count = Runtime.getRuntime().availableProcessors().coerceAtMost(63)
        fun readAll(name: String) = (0 until count).map { cpu ->
            File(base, "cpu$cpu/$name").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull() ?: -1L
        }
        efficiencyMask(readAll("cpufreq/cpuinfo_max_freq"))
            ?: efficiencyMask(readAll("cpu_capacity"))
            ?: efficiencyMaskFromParts(parseCpuParts(File("/proc/cpuinfo").readText()))
    }.getOrNull()
}
