package com.alpdroid.app

import java.util.concurrent.atomic.AtomicLong

/** Cheap counters for the terminal renderer, read by the resource manager to show real numbers
 *  (frames/s, time per frame, how many repaints were skipped because nothing visible changed). */
object RenderStats {
    class Snapshot(val drawn: Long, val skipped: Long, val drawNanos: Long)

    private val drawn = AtomicLong()
    private val skipped = AtomicLong()
    private val drawNanos = AtomicLong()

    fun frameDrawn(nanos: Long) { drawn.incrementAndGet(); drawNanos.addAndGet(nanos) }

    fun frameSkipped() { skipped.incrementAndGet() }

    fun snapshot() = Snapshot(drawn.get(), skipped.get(), drawNanos.get())
}
