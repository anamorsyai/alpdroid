package com.alpdroid.app

import java.io.OutputStream

/**
 * Writes pasted text to a session in bounded chunks instead of one giant write, so a long paste can
 * be abandoned part-way (the user pressed Ctrl+C because the program isn't reading, or pasted the
 * wrong thing) and never leaves the program stuck inside a bracketed-paste block.
 *
 * Runs on a session's writer thread; blocking on a full pipe is expected and is the natural pacing
 * (the receiving program consumes at its own speed). [isCancelled] is checked between chunks.
 */
object PasteWriter {
    const val CHUNK_BYTES = 8 * 1024
    private val START = "\u001B[200~".toByteArray(Charsets.US_ASCII)
    private val END = "\u001B[201~".toByteArray(Charsets.US_ASCII)

    /** Returns the number of payload bytes written (excluding the bracketed-paste markers). The end
     *  marker is always written when [bracketed], including after a cancel, so the receiving
     *  program leaves paste mode instead of swallowing everything typed next. */
    fun write(
        out: OutputStream,
        data: ByteArray,
        bracketed: Boolean,
        chunkBytes: Int = CHUNK_BYTES,
        isCancelled: () -> Boolean = { false },
    ): Int {
        var written = 0
        if (bracketed) { out.write(START); out.flush() }
        try {
            while (written < data.size && !isCancelled()) {
                val n = minOf(chunkBytes, data.size - written)
                out.write(data, written, n)
                out.flush()
                written += n
            }
        } finally {
            if (bracketed) { out.write(END); out.flush() }
        }
        return written
    }
}
