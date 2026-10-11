package com.alpdroid.proxy

/** One WebSocket frame, or one Server-Sent Event, seen passing through the proxy. */
class WsFrame(
    val index: Int,
    val timestampMs: Long,
    /** True for browser -> server; false for server -> browser (and for every SSE event). */
    val fromClient: Boolean,
    /** 0 continuation, 1 text, 2 binary, 8 close, 9 ping, 10 pong; SSE events are reported as 1. */
    val opcode: Int,
    val fin: Boolean,
    /** The frame's real payload length (the bytes kept in [payload] may be fewer: see [truncated]). */
    val length: Long,
    val payload: ByteArray,
    val truncated: Boolean,
) {
    val opcodeName: String get() = when (opcode) { 0 -> "continuation"; 1 -> "text"; 2 -> "binary"; 8 -> "close"; 9 -> "ping"; 10 -> "pong"; else -> "op$opcode" }
}

/** The frames of one live connection, bounded so a chatty socket cannot grow memory without limit (the oldest go first). */
class WebSocketFrames(val kind: String, private val maxFrames: Int = 2000, private val maxBytes: Long = 8L * 1024 * 1024) {
    private val frames = ArrayDeque<WsFrame>()
    private var bytes = 0L
    private var next = 0

    /** How many frames have been seen in total, including ones already dropped from the window. */
    val total: Int @Synchronized get() = next

    @Synchronized fun add(fromClient: Boolean, opcode: Int, fin: Boolean, length: Long, payload: ByteArray, truncated: Boolean) {
        frames.addLast(WsFrame(next++, System.currentTimeMillis(), fromClient, opcode, fin, length, payload, truncated))
        bytes += payload.size + 64
        while (frames.size > maxFrames || (bytes > maxBytes && frames.size > 1)) bytes -= frames.removeFirst().payload.size + 64
    }

    /** Frames with index >= [since], at most [limit]. */
    @Synchronized fun snapshot(since: Int = 0, limit: Int = Int.MAX_VALUE): List<WsFrame> = frames.filter { it.index >= since }.take(limit)
}

/** All connections whose frames are being kept, keyed by the History entry's id. The oldest are forgotten past [max]. */
class WebSocketRegistry(private val max: Int = 64) {
    private val map = object : LinkedHashMap<String, WebSocketFrames>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebSocketFrames>?) = size > max
    }

    @Synchronized fun open(id: String, kind: String): WebSocketFrames = WebSocketFrames(kind).also { map[id] = it }
    @Synchronized operator fun get(id: String): WebSocketFrames? = map[id]
    @Synchronized fun remove(id: String) { map.remove(id) }
    @Synchronized fun clear() { map.clear() }
}

/**
 * Reads WebSocket frames (RFC 6455) out of the bytes flowing in one direction, in chunks of any size, and reports each to [sink]
 * as it completes. It only looks: the bytes are relayed untouched by the caller. If the stream stops looking like WebSocket
 * (an impossible opcode or length) the parser gives up quietly ([broken]) rather than guess.
 */
class WsFrameParser(private val fromClient: Boolean, private val payloadCap: Int = 64 * 1024, private val sink: (WsFrame) -> Unit) {
    var broken = false
        private set

    private val header = ByteArray(14)
    private var headerLen = 0
    private var need = 2
    private var opcode = 0
    private var fin = false
    private var masked = false
    private val mask = ByteArray(4)
    private var length = 0L
    private var seen = 0L
    private var inPayload = false
    private var buf = ByteArray(0)
    private var kept = 0
    private var index = 0

    fun feed(data: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end && !broken) {
            if (!inPayload) {
                header[headerLen++] = data[i++]
                if (headerLen == 2) {
                    fin = header[0].toInt() and 0x80 != 0
                    opcode = header[0].toInt() and 0x0f
                    masked = header[1].toInt() and 0x80 != 0
                    val l7 = header[1].toInt() and 0x7f
                    need = 2 + (if (l7 == 126) 2 else if (l7 == 127) 8 else 0) + (if (masked) 4 else 0)
                    if (opcode in 3..7 || opcode in 11..15) { broken = true; return }
                }
                if (headerLen >= 2 && headerLen == need) {
                    val l7 = header[1].toInt() and 0x7f
                    var p = 2
                    length = when (l7) {
                        126 -> { val v = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff); p = 4; v.toLong() }
                        127 -> { var v = 0L; for (k in 2..9) v = (v shl 8) or (header[k].toLong() and 0xff); p = 10; v }
                        else -> l7.toLong()
                    }
                    if (length < 0 || length > (1L shl 31) || (opcode >= 8 && length > 125)) { broken = true; return }
                    if (masked) for (k in 0..3) mask[k] = header[p + k]
                    inPayload = true; seen = 0; kept = 0
                    buf = ByteArray(minOf(length, payloadCap.toLong()).toInt())
                    if (length == 0L) finish()
                }
            } else {
                val take = minOf((end - i).toLong(), length - seen).toInt()
                // Keep the first payloadCap bytes (unmasked); the rest is only counted.
                if (kept < buf.size) {
                    val n = minOf(take, buf.size - kept)
                    for (k in 0 until n) buf[kept + k] = if (masked) (data[i + k].toInt() xor mask[((seen + k) % 4).toInt()].toInt()).toByte() else data[i + k]
                    kept += n
                }
                seen += take; i += take
                if (seen == length) finish()
            }
        }
    }

    private fun finish() {
        sink(WsFrame(index++, System.currentTimeMillis(), fromClient, opcode, fin, length, buf.copyOf(kept), kept < length))
        inPayload = false; headerLen = 0; need = 2
    }
}

/** Reads Server-Sent Events (`text/event-stream`) out of the decoded body bytes; an event ends at a blank line. */
class SseParser(private val cap: Int = 64 * 1024, private val sink: (String, Boolean, Long) -> Unit) {
    private val cur = java.io.ByteArrayOutputStream()
    private var total = 0L
    private var prev = 0

    fun feed(data: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val b = data[i].toInt()
            if (b == '\r'.code) continue
            if (b == '\n'.code) {
                if (prev == '\n'.code) { flush() } else if (cur.size() < cap) cur.write(b)
                prev = b; total++
                continue
            }
            if (cur.size() < cap) cur.write(b)
            total++; prev = b
        }
    }

    private fun flush() {
        if (cur.size() > 0) sink(String(cur.toByteArray(), Charsets.UTF_8).trimEnd('\n'), total > cap, total)
        cur.reset(); total = 0
    }
}
