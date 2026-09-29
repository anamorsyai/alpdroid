package com.alpdroid.app

import java.io.File
import java.nio.file.Files

/**
 * Like [File.deleteRecursively], but never follows a symlink into its target — a symlink is
 * deleted as the link itself, never walked through. [File.isDirectory] (which
 * [File.deleteRecursively] relies on to decide whether to recurse) follows symlinks, so wiping a
 * tree that contains one — `ln -s /sdcard ~/storage` is a common Termux/Alpine habit, and every
 * busybox applet in a real Alpine rootfs is itself a symlink — could otherwise recurse through it
 * and delete whatever it actually points at: the user's real shared-storage files, in that
 * example, once storage access is granted and mounted into the guest.
 */
fun File.deleteRecursivelyNoFollow(): Boolean {
    if (Files.isSymbolicLink(toPath())) return delete()
    if (isDirectory) {
        listFiles()?.forEach { it.deleteRecursivelyNoFollow() }
    }
    return delete()
}
