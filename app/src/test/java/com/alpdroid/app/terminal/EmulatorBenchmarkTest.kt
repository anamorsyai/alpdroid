package com.alpdroid.app.terminal

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Throughput of the VT parser on TUI-like output (box drawing, truecolor SGR, cursor addressing, scrolling).
 * Skipped unless EMU_BENCH=1 — run it before and after touching the hot path:
 *   EMU_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*EmulatorBenchmarkTest' -i
 */
class EmulatorBenchmarkTest {
    private fun workload(frames: Int): ByteArray {
        val sb = StringBuilder()
        val rnd = java.util.Random(7)
        for (f in 0 until frames) {
            for (r in 1..40) {
                sb.append("\u001B[").append(r).append(";1H")
                var col = 0
                while (col < 118) {
                    sb.append("\u001B[38;2;").append(rnd.nextInt(256)).append(';').append(rnd.nextInt(256)).append(';').append(rnd.nextInt(256)).append('m')
                    when (rnd.nextInt(4)) {
                        0 -> sb.append("│ ──────── ╭──╮ ╰──╯ ").also { col += 22 }
                        1 -> sb.append("█▓▒░ progress ✓ done ").also { col += 21 }
                        2 -> sb.append("plain ascii text goes here ").also { col += 26 }
                        else -> sb.append("\u001B[1mbold\u001B[22m \u001B[4mul\u001B[24m ").also { col += 8 }
                    }
                }
                sb.append("\u001B[0m\u001B[K")
            }
            // a few full-screen scrolls like a log view: newlines at the bottom margin
            sb.append("\u001B[40;1H")
            repeat(6) { sb.append("line of log output with some text ").append(f).append("\r\n") }
            sb.append("\u001B[2L\u001B[1M")  // insert / delete lines
            sb.append("\u001B[1T")          // scroll down
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun parserThroughput() {
        assumeTrue(System.getenv("EMU_BENCH") == "1")
        val data = workload(300)
        fun runOnce(): Long {
            val t = TerminalEmulator(40, 120, maxScrollback = 1000)
            val start = System.nanoTime()
            var off = 0
            while (off < data.size) {
                val n = minOf(4096, data.size - off)
                t.feed(data.copyOfRange(off, off + n), n)
                off += n
            }
            return System.nanoTime() - start
        }
        repeat(4) { runOnce() } // warm-up: JIT
        val times = (1..8).map { runOnce() }.sorted()
        val med = times[times.size / 2]
        println("BENCH bytes=${data.size} median=${med / 1_000_000}ms best=${times[0] / 1_000_000}ms throughput=${"%.1f".format(data.size / 1e6 / (med / 1e9))}MB/s")
    }
}
