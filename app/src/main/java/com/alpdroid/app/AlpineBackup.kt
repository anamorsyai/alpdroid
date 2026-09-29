package com.alpdroid.app

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A full copy of the Alpine rootfs, as a plain `.tar.gz` a user can move off-device — cheap
 * insurance before a risky `apk upgrade`, or for carrying a set-up environment to a new device.
 * [AlpineRootfs] only ever needed to *read* a tarball (the one it downloads); this is the writer
 * half, plus the matching reader for restoring one back.
 */
object AlpineBackup {
    fun backupsDir(context: Context): File {
        // getExternalFilesDir() can return null if shared storage is temporarily unavailable
        // (unmounted, being shared over USB, ...) — filesDir always exists.
        val base = if (StorageAccess.isGranted(context)) StorageAccess.sharedStorageRoot() else context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "AlpDroidBackups").apply { mkdirs() }
    }

    fun backup(root: File, destTarGz: File, onEntry: (count: Int) -> Unit = {}) {
        GZIPOutputStream(destTarGz.outputStream().buffered()).use { gz ->
            val writer = UstarWriter(gz)
            addTree(writer, root, root, intArrayOf(0), onEntry)
            writer.finish()
        }
    }

    /** Live secrets that are regenerated at every session start — never copied into a backup that sits
     *  in shared storage: the agent-API token and the LAN opencode-web password. */
    private val NEVER_BACKED_UP = setOf("etc/alpdroid/bridge", "root/.opencode-web.env")

    private fun addTree(writer: UstarWriter, base: File, file: File, count: IntArray, onEntry: (Int) -> Unit) {
        val relative = if (file == base) "" else file.relativeTo(base).path
        if (relative in NEVER_BACKED_UP) return
        if (relative.isNotEmpty()) {
            // The real permission bits, not a guessed 755/644/755 — a restored ~/.ssh/id_* at 0644
            // instead of its real 0600 gets rejected outright by ssh ("UNPROTECTED PRIVATE KEY
            // FILE"), and setuid/setgid bits or a 0700 home/.ssh directory were silently dropped
            // the same way. Also doubles as the one reliable way to tell a FIFO or socket apart
            // from a regular file before ever trying to read it below.
            val lstat = runCatching { Os.lstat(file.path) }.getOrNull()
            val mode = lstat?.st_mode?.and(0xFFF)
                ?: (if (file.isDirectory) 0b111101101 else if (file.canExecute()) 0b111101101 else 0b110100100)
            when {
                Files.isSymbolicLink(file.toPath()) -> {
                    val target = Os.readlink(file.absolutePath)
                    writer.writeSymlink(relative, target)
                    onEntry(++count[0])
                    return
                }
                file.isDirectory -> writer.writeDirectory(relative, mode)
                // A FIFO (gpg-agent, some daemons drop these in a run/ dir) blocks forever on
                // open/read since nothing else has it open for writing — wedging the single
                // background executor this runs on, so no new tab can even start until the backup
                // is force-killed. A socket throws outright. Neither has meaningful "contents" to
                // preserve as a file entry anyway, so both are simply skipped rather than storing
                // (or hanging trying to read) something a restore couldn't recreate as-is either.
                lstat != null && !OsConstants.S_ISREG(lstat.st_mode) -> return
                else -> writer.writeFile(relative, file, mode)
            }
            onEntry(++count[0])
        }
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach { addTree(writer, base, it, count, onEntry) }
        }
    }

    /** Wipes [destRoot] first — a partial restore mixed with whatever was there before would be
     *  worse than a clean failure. */
    fun restore(srcTarGz: File, destRoot: File, onEntry: (count: Int) -> Unit = {}) {
        destRoot.deleteRecursivelyNoFollow()
        destRoot.mkdirs()
        var count = 0
        // Compared lexically (Path.normalize(), never touching the filesystem) rather than via
        // File.canonicalPath, which resolves real symlinks on disk — including ones THIS SAME
        // restore already extracted moments earlier from entries earlier in the archive. A crafted
        // archive (this reads any .tar.gz the user picks, not just ones this app wrote) could plant
        // a symlink entry pointing outside destRoot, followed by a file entry whose path runs
        // through that symlink — canonicalPath would legitimately resolve through it and pass the
        // check, since by then the escape really does exist on disk. Pure lexical comparison of the
        // declared name never depends on what's already been written.
        val rootNormalized = destRoot.toPath().normalize()
        GZIPInputStream(srcTarGz.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            while (true) {
                readFully(input, header)
                if (header.all { it == 0.toByte() }) break
                val name = ustarName(header)
                val mode = header.readString(100, 8).trimEnd('\u0000', ' ').toIntOrNull(8) ?: 0
                val type = header[156].toInt().toChar()
                val linkName = header.readString(157, 100).trimEnd('\u0000')
                val size = header.readString(124, 12).trimEnd('\u0000', ' ').toLongOrNull(8) ?: 0L

                if (name.isEmpty()) { skipPadding(input, size); continue }
                val dest = File(destRoot, name)
                val destNormalized = dest.toPath().normalize()
                if (destNormalized != rootNormalized && !destNormalized.startsWith(rootNormalized)) {
                    throw SecurityException("backup entry escapes destination: $name")
                }

                when (type) {
                    // 0xFFF (not 0b1111111111/0o1777) so setuid/setgid survive a restore too, not
                    // just the sticky bit and permission bits — matching addTree()'s own mask above.
                    '5' -> { dest.mkdirs(); runCatching { Os.chmod(dest.absolutePath, mode and 0xFFF) } }
                    '0', '\u0000' -> {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> copySized(input, out, size) }
                        skipPadding(input, size)
                        runCatching { Os.chmod(dest.absolutePath, mode and 0xFFF) }
                    }
                    '2' -> {
                        // The entry's own path was already checked above, but that says nothing
                        // about where the symlink itself POINTS — a crafted archive could plant a
                        // symlink whose path is safely inside destRoot but whose target is an
                        // absolute path or a "../"-escaping relative one, reaching anywhere else
                        // on the filesystem once something (proot, the file browser) follows it.
                        // An absolute target means absolute *inside the guest rootfs* (that's what
                        // every symlink in a real Alpine install already is — bin/sh -> /bin/busybox
                        // — since proot fakes "/" to be destRoot once the guest is running), not
                        // absolute on the host filesystem. Resolving it against the host's real "/"
                        // made every restore of an actual Alpine backup fail here: none of its
                        // absolute-target symlinks could ever resolve back under destRoot, so this
                        // threw a SecurityException on the very first one, well after destRoot had
                        // already been wiped by the deleteRecursivelyNoFollow() above. Same lexical
                        // (not canonical) comparison as the entry-path check above, for the same
                        // already-extracted-symlink reason.
                        val resolvedTarget = if (linkName.startsWith("/")) File(destRoot, linkName) else File(dest.parentFile, linkName)
                        val resolvedNormalized = resolvedTarget.toPath().normalize()
                        if (resolvedNormalized != rootNormalized && !resolvedNormalized.startsWith(rootNormalized)) {
                            throw SecurityException("backup symlink target escapes destination: $name -> $linkName")
                        }
                        dest.parentFile?.mkdirs()
                        dest.delete()
                        Os.symlink(linkName, dest.absolutePath)
                    }
                    else -> throw IllegalStateException("unsupported backup entry type '$type': $name")
                }
                onEntry(++count)
            }
        }
    }

    private fun ustarName(header: ByteArray): String {
        val prefix = header.readString(345, 155).trimEnd('\u0000')
        val name = header.readString(0, 100).trimEnd('\u0000')
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw java.io.EOFException("truncated backup archive")
            off += n
        }
    }

    private fun copySized(input: java.io.InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(32 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n == -1) throw java.io.EOFException("truncated backup entry")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipPadding(input: java.io.InputStream, size: Long) {
        var remaining = ((512 - (size % 512)) % 512).toInt()
        val buf = ByteArray(512)
        while (remaining > 0) {
            val n = input.read(buf, 0, remaining)
            if (n == -1) throw java.io.EOFException("truncated backup padding")
            remaining -= n
        }
    }

    private fun ByteArray.readString(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.UTF_8)
    }

    /** Minimal POSIX ustar writer: directories, regular files, symlinks — the same subset
     *  [AlpineRootfs]'s reader supports, since everything this ever writes is either an Alpine
     *  rootfs (which is only ever those three types) or read back by [restore] above. */
    private class UstarWriter(private val out: OutputStream) {
        fun writeDirectory(name: String, mode: Int = 0b111101101) = writeHeader(name, '5', 0, mode, "")

        fun writeFile(name: String, file: File, mode: Int) {
            val size = file.length()
            writeHeader(name, '0', size, mode, "")
            file.inputStream().use { input ->
                // Bounded to exactly the size already committed to the header above, not "until
                // EOF" — a session is very likely still live while a backup runs, and a file that
                // grows between the length() call and this read (a shell history file, a log) used
                // to write more bytes than the header declared, mis-framing every single entry that
                // followed it for the rest of the archive. A file that *shrank* instead is zero-
                // padded out to the declared size so the framing still holds.
                val buf = ByteArray(32 * 1024)
                var copied = 0L
                while (copied < size) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), size - copied).toInt())
                    if (n == -1) break
                    out.write(buf, 0, n)
                    copied += n
                }
                if (copied < size) out.write(ByteArray((size - copied).toInt()))
                padTo512(size)
            }
        }

        fun writeSymlink(name: String, target: String) = writeHeader(name, '2', 0, 0b111111111, target)

        fun finish() {
            val zeros = ByteArray(1024) // two zero blocks mark end-of-archive
            out.write(zeros)
        }

        private fun padTo512(size: Long) {
            val pad = ((512 - (size % 512)) % 512).toInt()
            if (pad > 0) out.write(ByteArray(pad))
        }

        private fun writeHeader(name: String, type: Char, size: Long, mode: Int, linkName: String) {
            val header = ByteArray(512)
            // Names over 100 bytes split across prefix(155)+name(100), same as GNU/POSIX tar —
            // needed once anything installed into the rootfs (node_modules, etc.) nests deep.
            val (prefix, shortName) = splitName(name)
            writeString(header, 0, 100, shortName)
            writeOctal(header, 100, 8, mode.toLong())
            writeOctal(header, 108, 8, 0) // uid
            writeOctal(header, 116, 8, 0) // gid
            writeOctal(header, 124, 12, size)
            writeOctal(header, 136, 12, System.currentTimeMillis() / 1000)
            for (i in 148 until 156) header[i] = ' '.code.toByte() // chksum placeholder
            header[156] = type.code.toByte()
            writeString(header, 157, 100, linkName)
            writeString(header, 257, 6, "ustar")
            writeString(header, 263, 2, "00")
            writeString(header, 345, 155, prefix)

            var checksum = 0
            for (b in header) checksum += (b.toInt() and 0xFF)
            writeOctal(header, 148, 8, checksum.toLong())
            header[154] = 0
            header[155] = ' '.code.toByte()

            out.write(header)
        }

        private fun splitName(name: String): Pair<String, String> {
            if (name.length <= 100) return "" to name
            // A valid ustar split needs a slash whose position leaves the trailing "name" field
            // (rejoined with a "/" separator, by ustarName()) at most 100 bytes AND the leading
            // "prefix" field at most 155 — namePart is name.substring(slash+1), length
            // name.length-slash-1, so slash must be >= name.length-101; prefixPart is
            // name.substring(0, slash), length slash, so slash must be <= 155.
            val earliestSlash = name.length - 101
            val latestSlash = minOf(155, name.length - 2)
            val slash = if (latestSlash >= 0) name.lastIndexOf('/', latestSlash) else -1
            if (slash < earliestSlash) {
                // No slash lands in the window ustar can represent at all (one path component
                // alone longer than 100 bytes) — truncate rather than corrupt the whole archive.
                return "" to name.takeLast(100)
            }
            val prefixPart = name.substring(0, slash)
            val namePart = name.substring(slash + 1)
            return prefixPart to namePart
        }

        private fun writeString(buf: ByteArray, offset: Int, maxLen: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val len = minOf(bytes.size, maxLen)
            System.arraycopy(bytes, 0, buf, offset, len)
        }

        private fun writeOctal(buf: ByteArray, offset: Int, length: Int, value: Long) {
            val octal = java.lang.Long.toOctalString(value)
            val padded = octal.padStart(length - 1, '0')
            writeString(buf, offset, length - 1, padded)
            buf[offset + length - 1] = 0
        }
    }
}
