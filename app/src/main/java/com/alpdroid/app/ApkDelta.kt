package com.alpdroid.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Rebuilds the new release APK from the APK that is already installed plus a small delta file (`*.adp`, made in CI by
 * `scripts/make_apk_delta.py`), so an update downloads only what changed. The rebuilt file must match the new APK's
 * SHA-256 recorded in the delta, otherwise it is refused and the caller falls back to the full download. Pure
 * java.io: unit-tested on a plain JVM.
 *
 * Format (big-endian): "ADP1" | sha256(base) 32 | sha256(new) 32 | size(new) u64 | zlib stream of operations
 * 'C' offset u64 length u64 (copy from the base) · 'I' length u64 + bytes (insert) · 'E' (end).
 */
object ApkDelta {
    class DeltaException(message: String) : Exception(message)

    private const val MAGIC = "ADP1"
    private const val MAX_APK_BYTES = 256L * 1024 * 1024

    /** The new APK's SHA-256 (lower-case hex) that [patch] promises, or null when it is not a delta file. */
    fun targetSha256(patch: File): String? = runCatching {
        DataInputStream(BufferedInputStream(patch.inputStream())).use { input ->
            val head = ByteArray(4).also { input.readFully(it) }
            if (String(head, Charsets.US_ASCII) != MAGIC) return@use null
            input.skipBytes(32)
            ByteArray(32).also { input.readFully(it) }.toHex()
        }
    }.getOrNull()

    /**
     * Writes the new APK to [out]. Throws [DeltaException] (and leaves [out] deleted) for anything that does not add
     * up: a different base APK than the delta was made for, a damaged or truncated delta, out-of-range copies, or a
     * result whose SHA-256 differs. [expectedSha256] (hex), when given, must also equal the one in the delta.
     */
    fun apply(base: File, patch: File, out: File, expectedSha256: String = "", onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        try {
            applyInternal(base, patch, out, expectedSha256, onProgress)
        } catch (e: DeltaException) {
            out.delete(); throw e
        } catch (e: Exception) {
            out.delete(); throw DeltaException("delta could not be applied: ${e.message}")
        }
    }

    private fun applyInternal(base: File, patch: File, out: File, expectedSha256: String, onProgress: (Long, Long) -> Unit) {
        DataInputStream(BufferedInputStream(patch.inputStream(), 64 * 1024)).use { raw ->
            val magic = ByteArray(4).also { raw.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw DeltaException("not a delta file")
            val baseSha = ByteArray(32).also { raw.readFully(it) }
            val newSha = ByteArray(32).also { raw.readFully(it) }
            val newSize = raw.readLong()
            if (newSize <= 0 || newSize > MAX_APK_BYTES) throw DeltaException("unreasonable target size")
            if (expectedSha256.isNotEmpty() && expectedSha256.lowercase() != newSha.toHex()) throw DeltaException("delta is for a different release")
            if (sha256(base).toHex() != baseSha.toHex()) throw DeltaException("the installed APK is not the one this delta was made for")

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            DataInputStream(BufferedInputStream(InflaterInputStream(raw), 64 * 1024)).use { ops ->
                RandomAccessFile(base, "r").use { src ->
                    out.outputStream().buffered(64 * 1024).use { sink ->
                        val buf = ByteArray(64 * 1024)
                        loop@ while (true) {
                            when (val op = ops.read()) {
                                'C'.code -> {
                                    var off = ops.readLong(); var len = ops.readLong()
                                    if (off < 0 || len < 0 || off + len > src.length() || written + len > newSize) throw DeltaException("copy outside the installed APK")
                                    src.seek(off)
                                    while (len > 0) {
                                        val n = src.read(buf, 0, minOf(len, buf.size.toLong()).toInt())
                                        if (n < 0) throw DeltaException("installed APK ended early")
                                        sink.write(buf, 0, n); digest.update(buf, 0, n)
                                        len -= n; written += n
                                    }
                                }
                                'I'.code -> {
                                    var len = ops.readLong()
                                    if (len < 0 || written + len > newSize) throw DeltaException("insert beyond the target size")
                                    while (len > 0) {
                                        val n = ops.read(buf, 0, minOf(len, buf.size.toLong()).toInt())
                                        if (n < 0) throw EOFException("delta ended early")
                                        sink.write(buf, 0, n); digest.update(buf, 0, n)
                                        len -= n; written += n
                                    }
                                }
                                'E'.code -> break@loop
                                -1 -> throw EOFException("delta ended early")
                                else -> throw DeltaException("unknown operation $op")
                            }
                            onProgress(written, newSize)
                        }
                    }
                }
            }
            if (written != newSize) throw DeltaException("rebuilt APK has the wrong size")
            if (digest.digest().toHex() != newSha.toHex()) throw DeltaException("rebuilt APK does not match its checksum")
        }
    }

    private fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
