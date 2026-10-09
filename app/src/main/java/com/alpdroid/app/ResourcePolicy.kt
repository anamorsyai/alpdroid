package com.alpdroid.app

/**
 * The decisions of the resource manager, kept free of Android types so they can be unit-tested:
 * which power mode to run in, how /proc numbers turn into CPU/RAM figures, and when a session's
 * native helper counts as spinning.
 */
object ResourcePolicy {
    /** How often a terminal may repaint under sustained output (bursts are coalesced into one frame). */
    enum class Mode(val label: String, val frameIntervalMs: Long) {
        NORMAL("Normal", 33L), // ~30 fps: text doesn't look different above this, and a full-screen app costs half
        ECO("Eco", 50L),       // ~20 fps: battery saver, low battery, warm phone
        COOL("Cool", 100L),    // ~10 fps: phone is hot — shed load until it recovers
    }

    // android.os.PowerManager.THERMAL_STATUS_* (API 29+); 0 when the device doesn't report it.
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    fun mode(thermalStatus: Int, powerSave: Boolean, batteryPercent: Int, charging: Boolean): Mode = when {
        thermalStatus >= THERMAL_SEVERE -> Mode.COOL
        thermalStatus >= THERMAL_MODERATE -> Mode.ECO
        powerSave -> Mode.ECO
        !charging && batteryPercent in 0..14 -> Mode.ECO
        else -> Mode.NORMAL
    }

    /** CPU use of one process as a percentage of one core, from its change in utime+stime. */
    fun cpuPercent(deltaJiffies: Long, deltaMs: Long, clockTicksPerSecond: Int = 100): Double {
        if (deltaMs <= 0 || deltaJiffies < 0) return 0.0
        return deltaJiffies * 1000.0 / clockTicksPerSecond / deltaMs * 100.0
    }

    class ProcStat(val ppid: Int, val cpuJiffies: Long)

    /** Parses /proc/<pid>/stat. The command name (field 2) sits in parentheses and may itself contain
     *  spaces and parentheses, so everything is read relative to the LAST ')'. */
    fun parseStat(text: String): ProcStat? {
        val close = text.lastIndexOf(')')
        if (close < 0) return null
        val f = text.substring(close + 1).trim().split(' ')
        // f[0] = state (field 3), f[1] = ppid (field 4), f[11] = utime (field 14), f[12] = stime (field 15)
        if (f.size < 13) return null
        val ppid = f[1].toIntOrNull() ?: return null
        val utime = f[11].toLongOrNull() ?: return null
        val stime = f[12].toLongOrNull() ?: return null
        return ProcStat(ppid, utime + stime)
    }

    /** Resident set size in bytes from /proc/<pid>/statm (second field is pages). */
    fun parseStatmRssBytes(text: String, pageSize: Int = 4096): Long {
        val pages = text.trim().split(' ').getOrNull(1)?.toLongOrNull() ?: return 0L
        return pages * pageSize
    }

    /** MemAvailable from /proc/meminfo in MB, or -1 when it is not there. */
    fun parseMemAvailableMb(meminfo: String): Long {
        val kb = meminfo.lineSequence().firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: return -1L
        return kb / 1024
    }

    /**
     * What to tell the user when a session died of SIGKILL (137) while the app itself lived on — the
     * numbers decide which of the two usual killers it was: more than ~28 processes at the last sample
     * (or at its peak) means Android's child-process limit (32), very little free memory means the
     * low-memory killer, neither means the phone's own battery manager.
     */
    fun explainKill(processes: Int, peakProcesses: Int, memAvailableMb: Long, busiest: String = ""): String {
        val facts = buildString {
            if (peakProcesses > 0) append("; processes: ").append(processes).append(" now, ").append(peakProcesses).append(" at most (Android's limit is 32)")
            if (memAvailableMb >= 0) append("; free memory ").append(memAvailableMb).append(" MB")
            if (busiest.isNotEmpty()) append("; busiest: ").append(busiest)
        }
        val likely = when {
            maxOf(processes, peakProcesses) >= 28 -> " — most likely the child-process limit: turn on Developer options → \"Disable child process restrictions\" (Android 14+) or use the adb fix in Settings"
            memAvailableMb in 0..400 -> " — most likely low memory: close other apps or run fewer programs at once"
            else -> " — Android stopped it (often the phone's battery manager: allow background activity for AlpDroid)"
        }
        return "killed (SIGKILL$facts)$likely"
    }

    /** All descendants of [root] (excluding it) given a pid -> ppid map. */
    fun descendants(root: Int, parentOf: Map<Int, Int>): Set<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        for ((pid, ppid) in parentOf) children.getOrPut(ppid) { ArrayList() }.add(pid)
        val out = LinkedHashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            for (c in children[stack.removeLast()].orEmpty()) if (out.add(c)) stack.add(c)
        }
        return out
    }

    /**
     * Flags a session whose native bridge keeps a core busy while everything it relays for is idle —
     * the signature of the bridge spinning (the frozen-session bug fixed in 1.7.46), as opposed to a
     * legitimately busy guest, which shows up as CPU in the guest processes. Needs several samples in
     * a row so a short burst of output (cat of a big file) never trips it.
     */
    class RunawayDetector(
        private val bridgeThresholdPercent: Double = 60.0,
        private val guestIdleBelowPercent: Double = 10.0,
        private val samplesNeeded: Int = 3,
    ) {
        private var streak = 0

        /** Returns true once the condition has held for [samplesNeeded] consecutive samples. */
        fun update(bridgePercent: Double, guestPercent: Double): Boolean {
            streak = if (bridgePercent >= bridgeThresholdPercent && guestPercent < guestIdleBelowPercent) streak + 1 else 0
            return streak >= samplesNeeded
        }

        fun reset() { streak = 0 }
    }
}
