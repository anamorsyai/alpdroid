package com.alpdroid.app.files

import com.alpdroid.app.deleteRecursivelyNoFollow
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Pure file-manipulation logic — no Android UI/Context dependency, so it works identically
 *  whether the paths involved are under Android's own shared storage or the Alpine rootfs;
 *  both are just directories on the same underlying filesystem from Java's point of view. */
object FileOps {

    /** Copies [src] into [dstDir], auto-renaming ("name (1).ext") on a collision rather than
     *  silently overwriting — the common expectation for a file manager's plain Copy action. */
    fun copy(src: File, dstDir: File): File {
        if (src.isDirectory) checkNotIntoSelf(src, dstDir)
        val dest = uniqueDestination(dstDir, src.name)
        // Checked first, same as copyRecursively()'s own nested check below — src.isDirectory
        // follows a symlink, so without this a top-level symlinked directory got deep-copied
        // (the *target's* actual contents duplicated into a brand new real directory) instead of
        // being copied as the link it is, inconsistent with how every symlink nested one level
        // deeper is already handled. A dangling top-level link (every busybox applet symlink in a
        // real Alpine rootfs whose target lives outside whatever subtree got selected) used to hit
        // the `else` branch and throw trying to open a target that doesn't exist, instead of just
        // recreating the link itself, which needs no target to exist at all.
        when {
            Files.isSymbolicLink(src.toPath()) -> Files.createSymbolicLink(dest.toPath(), Files.readSymbolicLink(src.toPath()))
            src.isDirectory -> copyRecursively(src, dest)
            else -> src.copyTo(dest)
        }
        return dest
    }

    /** A folder copied or moved into itself (or one of its own subdirectories) would otherwise
     *  have copyRecursively() create the destination *inside* the very tree it's still walking,
     *  recursing into its own output forever until the disk fills or a path-length limit is hit —
     *  and for move(), the following delete(src) would then wipe out everything, copy included. */
    private fun checkNotIntoSelf(src: File, dstDir: File) {
        val srcPath = src.canonicalPath
        val dstPath = dstDir.canonicalPath
        if (dstPath == srcPath || dstPath.startsWith("$srcPath${File.separator}")) {
            throw IllegalArgumentException("can't copy or move \"${src.name}\" into itself")
        }
    }

    private fun copyRecursively(srcDir: File, dstDir: File) {
        dstDir.mkdirs()
        srcDir.listFiles()?.forEach { child ->
            val target = File(dstDir, child.name)
            when {
                // File.isDirectory follows symlinks — without checking this first, a symlink back
                // to itself or an ancestor (the Alpine rootfs this file manager also browses is,
                // per AlpineRootfs's own doc comment, "mostly symlinks") recurses into itself
                // forever: a hang, then a StackOverflowError, instead of copying the link as a
                // link the way a real file manager (and AlpineBackup's own tar writer) does.
                Files.isSymbolicLink(child.toPath()) -> {
                    target.delete()
                    // Android's shared-storage FUSE/sdcardfs layer doesn't support symlinks at
                    // all — createSymbolicLink() throws there, which used to fail an entire
                    // Alpine-folder-to-shared-storage copy partway through over the very first
                    // symlink it hit (bin/, usr/bin/, and most other real Alpine directories are
                    // full of them). Falls back to copying whatever the link actually resolves to
                    // — at least something usable on a filesystem that can't represent the link
                    // itself — rather than aborting the whole copy.
                    runCatching { Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(child.toPath())) }
                        .onFailure {
                            when {
                                child.isDirectory -> copyRecursively(child, target)
                                child.isFile -> child.copyTo(target, overwrite = true)
                                // else: a dangling link — nothing valid to copy either way.
                            }
                        }
                }
                child.isDirectory -> copyRecursively(child, target)
                else -> child.copyTo(target, overwrite = true)
            }
        }
    }

    /** Rename (same filesystem, near-instant) when possible, falling back to copy+delete for a
     *  genuine cross-storage move (Android shared storage and the Alpine rootfs are both under
     *  the same real filesystem here, so the fast path is actually what runs in practice). */
    fun move(src: File, dstDir: File): File {
        val dest = uniqueDestination(dstDir, src.name)
        if (src.renameTo(dest)) return dest
        val copied = copy(src, dstDir)
        // A failed delete after a successful cross-storage copy used to return "moved"
        // while the source still existed — a silent duplicate the caller then displayed
        // as if the original were gone.
        if (!delete(src)) throw IllegalStateException("copied to ${copied.name} but could not remove the original")
        return copied
    }

    // Files.isSymbolicLink() checked first (not file.isDirectory, which follows the link) — a
    // symlinked folder selected for delete would otherwise recurse into and wipe whatever it
    // actually points at, which is exactly how a real Alpine rootfs is laid out (every busybox
    // applet is a symlink) and how a user's own `ln -s /sdcard ~/storage` habit is shaped too.
    fun delete(file: File): Boolean =
        if (Files.isSymbolicLink(file.toPath())) file.delete()
        else if (file.isDirectory) file.deleteRecursivelyNoFollow()
        else file.delete()

    fun rename(file: File, newName: String): File {
        // A name is one path segment, not a path: "../", "/" or "a/b" would escape the
        // current directory (the dialog passes raw text straight through here).
        require(newName.isNotBlank() && !newName.contains('/') && !newName.contains('\u0000') && newName != "." && newName != "..") {
            "invalid name: \"$newName\""
        }
        val dest = File(file.parentFile, newName)
        if (dest.exists()) throw IllegalArgumentException("\"$newName\" already exists")
        if (!file.renameTo(dest)) throw IllegalStateException("rename failed")
        return dest
    }

    fun zip(source: File, destZip: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) =
        zip(listOf(source), destZip, onProgress)

    /** Each source becomes its own top-level entry (or subtree) in the archive — the natural
     *  result of zipping a multi-selection, same as any desktop file manager's "Compress".
     *  [onProgress] fires after each top-level source finishes — the only granularity available
     *  without pre-walking every directory just to count files before actually zipping them. */
    fun zip(sources: List<File>, destZip: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
            for ((index, source) in sources.withIndex()) {
                // A symlink the user explicitly selected and asked to compress is followed (its
                // real content zipped), same as most desktop file managers treat a symlink you
                // point "Compress" at directly — only symlinks encountered *while recursing* into
                // a directory (zipDirectory() below) are skipped, since that's the case that can
                // self-reference and loop forever. Skipping here too used to mean compressing a
                // single selected symlink (or a selection made up only of symlinks) silently
                // produced an empty zip with no indication anything was skipped.
                when {
                    source.isDirectory -> zipDirectory(source, source.name, zos)
                    source.isFile -> zipFile(source, source.name, zos)
                    else -> continue // a dangling symlink (or something else unreadable) — nothing to zip
                }
                onProgress(index + 1, sources.size)
            }
        }
    }

    private fun zipDirectory(dir: File, entryPrefix: String, zos: ZipOutputStream) {
        // Symlinks filtered out BEFORE the empty check, not just before the loop below — a
        // directory whose children are all symlinks (as full of them as any Alpine rootfs
        // directory tends to be) used to see a non-empty listFiles(), skip the "write an empty
        // entry" branch, then skip every child in the loop too — disappearing from the zip
        // entirely instead of appearing as an (admittedly empty) folder.
        val children = (dir.listFiles() ?: emptyArray()).filterNot { Files.isSymbolicLink(it.toPath()) }
        if (children.isEmpty()) {
            zos.putNextEntry(ZipEntry("$entryPrefix/"))
            zos.closeEntry()
        }
        for (child in children) {
            val entryName = "$entryPrefix/${child.name}"
            // File.isDirectory follows symlinks — already filtered out above, so every child here
            // is a real file or directory, never a link. Un-filtered, a self-referential link
            // (common throughout the Alpine rootfs this also zips) would recurse into itself
            // forever, and an absolute-target link would resolve against Android's real
            // filesystem root instead of the rootfs and just throw FileNotFoundException — either
            // way, zipping bin/, usr/bin/, or any directory full of Alpine's own busybox applet
            // symlinks always failed before this was filtered.
            if (child.isDirectory) zipDirectory(child, entryName, zos) else zipFile(child, entryName, zos)
        }
    }

    private fun zipFile(file: File, entryName: String, zos: ZipOutputStream) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** Extracts into a new subdirectory named after the archive (matching most desktop file
     *  managers' default "Extract" behavior) rather than dumping entries into the current dir. */
    fun unzip(zipFile: File, destDir: File, onEntry: (count: Int) -> Unit = {}): File {
        val targetDir = uniqueDestination(destDir, zipFile.nameWithoutExtension)
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalPath
        var count = 0
        var totalBytes = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Zip-bomb guard: a tiny archive can declare gigabytes of highly-compressible
                // output and fill the phone's storage. Abort before writing past the caps.
                if (++count > MAX_UNZIP_ENTRIES) throw IllegalStateException("zip has more than $MAX_UNZIP_ENTRIES entries")
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith("$canonicalTarget${File.separator}") && outFile.canonicalPath != canonicalTarget) {
                    throw SecurityException("zip entry escapes destination: ${entry.name}")
                }
                // canonicalPath resolves symlinks planted by earlier entries (or a racing guest
                // in the live rootfs) and can still pass while writing onto the host — walk the
                // parent chain NOFOLLOW for every entry, not just once up front.
                if (hasSymlinkParent(outFile, targetDir)) {
                    throw SecurityException("zip entry runs through a symlink: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (Files.isSymbolicLink(outFile.toPath())) throw SecurityException("zip entry is a directory over a symlink: ${entry.name}")
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var n = zis.read(buf)
                        while (n != -1) {
                            totalBytes += n
                            if (totalBytes > MAX_UNZIP_BYTES) throw IllegalStateException("zip extracts more than ${MAX_UNZIP_BYTES / (1024 * 1024)} MB")
                            out.write(buf, 0, n)
                            n = zis.read(buf)
                        }
                    }
                }
                zis.closeEntry()
                onEntry(count)
                entry = zis.nextEntry
            }
        }
        return targetDir
    }

    private fun uniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    /** True when any component of outFile's parent chain below target is a symlink (NOFOLLOW).
     *  Complements the canonicalPath containment check above, which resolves links. */
    private fun hasSymlinkParent(outFile: File, target: File): Boolean {
        val targetPath = target.absolutePath
        var p = outFile.parentFile
        while (p != null && p != target && p.absolutePath.startsWith(targetPath)) {
            if (Files.isSymbolicLink(p.toPath())) return true
            p = p.parentFile
        }
        return false
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        return "%.1f %s".format(value, units[unitIndex])
    }

    /** Zip-bomb caps for [unzip]: a few-KB archive can declare GBs of output. */
    private const val MAX_UNZIP_ENTRIES = 100_000
    private const val MAX_UNZIP_BYTES = 2L * 1024 * 1024 * 1024
}
