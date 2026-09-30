package com.alpdroid.app.files

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.alpdroid.app.AlpineRootfs
import com.alpdroid.app.OperationNotifications
import com.alpdroid.app.R
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The left-swipe drawer's content: browses two roots — Android's shared storage and the Alpine
 * rootfs — with the same list. Both are just directories on the same real filesystem from
 * Java's point of view, so every operation (via [FileOps]) works identically across them; a
 * copy from one "root" to the other is really just a copy between two ordinary paths.
 *
 * Takes its views injected rather than owning an Activity/layout of its own — this used to be a
 * separate screen (FileManagerActivity), but embedding it directly as the drawer's content is
 * more useful than a link to a separate screen, and session switching already has its own home
 * in the top tab bar, so there's no second panel competing for the same edge.
 */
class FileBrowserPanel(
    private val activity: Activity,
    private val androidButton: Button,
    private val alpineButton: Button,
    private val upButton: ImageButton,
    private val pathText: TextView,
    private val listView: ListView,
    private val clipboardBar: View,
    private val clipboardText: TextView,
    pasteButton: Button,
    cancelButton: Button,
    private val selectionBarScroll: View,
    selectionBar: ViewGroup,
    /** Called whenever Android or Alpine is picked — lets the owner (the session manager tab
     *  shares this same drawer) switch back out of its own view without this panel needing to
     *  know that mode exists at all. */
    private val onFileRootSelected: () -> Unit = {},
    /** Called with the guest-side path of a folder to open a new terminal tab in. */
    private val onOpenTerminalHere: (String) -> Unit = {},
) {
    private enum class Root { ANDROID, ALPINE }

    private var root = Root.ANDROID
    private lateinit var currentDir: File
    private var entries: List<File> = emptyList()

    // The Alpine rootfs's own /sdcard is just an empty directory on the real filesystem — a
    // bind-mount target that only means anything to a *running proot session's* traced syscalls,
    // never to this plain-Java browser, which always sees it as empty. Tapping into it instead
    // aliases straight to the real shared storage (the same content the mount actually shows a
    // shell), and this flag remembers that so "up" pops back into the Alpine listing rather than
    // continuing to walk shared storage's own real parent directories.
    private var sdcardAlias = false

    private data class Clipboard(val files: List<File>, val cut: Boolean)
    private var clipboard: Clipboard? = null

    // Multi-select: long-press a row to enter, tap other rows to toggle, the selection toolbar
    // (Copy/Move/Delete/Zip/Share) operates on the whole set at once.
    private val selected = mutableSetOf<File>()
    private val selectionCount: TextView = selectionBar.findViewById(R.id.fbSelectionCount)

    // Copy/move/delete/compress/extract can all be slow (a large folder, a big video file) —
    // running them inline on the UI thread (as this originally did) risked freezing the list or
    // even an ANR. Everything below routes through this instead.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // A plain shutdown(), not shutdownNow() — MainActivity.onDestroy() calls this on every
    // destruction, including the system recreating the Activity (not just a deliberate exit), so
    // shutdownNow()'s "drain and discard whatever's still queued, interrupt whatever's running"
    // used to silently drop a copy/move/compress that was queued behind another operation, with
    // no error shown, the moment the Activity got recreated out from under it. A graceful
    // shutdown() still lets this executor's single thread terminate once its queue drains —
    // nothing leaks — it just lets already-queued work actually finish first.
    fun shutdown() = ioExecutor.shutdown()

    /** Runs [block] and posts the operation's final notification from the worker thread itself. The UI
     *  callbacks are skipped once the Activity is gone (the process lives on under the keep-alive
     *  service), and a finish() that only lived there left a non-dismissible "Copying…" up forever. */
    private fun <T> tracked(notifId: Int, title: String, block: () -> T): T = try {
        block().also { OperationNotifications.finish(activity, notifId, title, "Done", true) }
    } catch (e: Throwable) {
        OperationNotifications.finish(activity, notifId, title, e.message ?: "Failed", false)
        throw e
    }

    private fun runInBackground(action: () -> Unit, onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        ioExecutor.execute {
            val result = runCatching(action)
            activity.runOnUiThread {
                // The Activity can finish (or be destroyed by the system) while this was still
                // running in the background — onSuccess/onError often show a dialog or a Toast
                // tied to this Activity's window, and a MaterialAlertDialogBuilder built against
                // a finished/destroyed Activity throws BadTokenException rather than just no-op-ing.
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { onSuccess() }.onFailure { onError(it) }
            }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_file_row, parent, false)
            val file = entries[position]
            view.findViewById<ImageView>(R.id.rowIcon).apply {
                setImageResource(iconResFor(file))
                imageTintList = ColorStateList.valueOf(iconTintFor(file))
            }
            view.findViewById<TextView>(R.id.rowName).text = file.name
            view.findViewById<TextView>(R.id.rowSubtitle).text = subtitleFor(file)
            view.setBackgroundColor(if (file in selected) 0x333ED0B8 else 0)
            view.setOnClickListener { if (selected.isEmpty()) onRowTap(file) else toggleSelection(file) }
            view.setOnLongClickListener {
                if (selected.isEmpty()) showFileMenu(file, view) else toggleSelection(file)
                true
            }
            return view
        }
    }

    init {
        currentDir = androidRoot()
        androidButton.setOnClickListener { switchRoot(Root.ANDROID) }
        alpineButton.setOnClickListener { switchRoot(Root.ALPINE) }
        upButton.setOnClickListener { navigateUp() }
        pasteButton.setOnClickListener { pasteClipboard() }
        cancelButton.setOnClickListener { clipboard = null; updateClipboardBar() }
        listView.adapter = adapter
        selectionBar.findViewById<ImageButton>(R.id.fbSelectCopyButton).setOnClickListener {
            clipboard = Clipboard(selected.toList(), cut = false)
            updateClipboardBar()
            clearSelection()
        }
        selectionBar.findViewById<ImageButton>(R.id.fbSelectMoveButton).setOnClickListener {
            clipboard = Clipboard(selected.toList(), cut = true)
            updateClipboardBar()
            clearSelection()
        }
        selectionBar.findViewById<ImageButton>(R.id.fbSelectDeleteButton).setOnClickListener { deleteSelectedConfirm() }
        selectionBar.findViewById<ImageButton>(R.id.fbSelectCompressButton).setOnClickListener { compressSelected() }
        selectionBar.findViewById<ImageButton>(R.id.fbSelectShareButton).setOnClickListener { shareSelected() }
        selectionBar.findViewById<ImageButton>(R.id.fbSelectCancelButton).setOnClickListener { clearSelection() }
        switchRoot(Root.ANDROID)
    }

    private fun toggleSelection(file: File) {
        if (!selected.add(file)) selected.remove(file)
        selectionBarScroll.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.text = "${selected.size} selected"
        adapter.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        selectionBarScroll.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun deleteSelectedConfirm() {
        val files = selected.toList()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete ${files.size} item${if (files.size == 1) "" else "s"}?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                runInBackground({ files.forEach { FileOps.delete(it) } }, { clearSelection(); reload() }, { toast("Delete failed") })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun compressSelected() {
        val files = selected.toList()
        val destDir = currentDir
        toast("Compressing…")
        val notifId = OperationNotifications.newId()
        OperationNotifications.progress(activity, notifId, "Compressing", "0 / ${files.size}")
        runInBackground(
            {
                tracked(notifId, "Compressing") {
                    val destZip = File(destDir, "archive-${System.currentTimeMillis()}.zip")
                    FileOps.zip(files, destZip) { done, total ->
                        OperationNotifications.progress(activity, notifId, "Compressing", "$done / $total")
                    }
                }
            },
            { clearSelection(); reload(); toast("Compressed") },
            { toast(it.message ?: "Compress failed") },
        )
    }

    private fun shareSelected() {
        val files = selected.toList()
        runCatching {
            val uris = ArrayList(files.map { uriFor(it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share"))
        }.onFailure { toast("Couldn't share these files") }
        clearSelection()
    }

    /** True if this consumed the back press (currently inside a subdirectory) — the caller
     *  falls through to its own back handling (e.g. closing the drawer) when this returns false. */
    fun onBackPressed(): Boolean {
        if (sdcardAlias && currentDir.canonicalPath == androidRoot().canonicalPath) {
            navigateUp()
            return true
        }
        val base = rootBase()
        if (base.isDirectory && currentDir.canonicalPath != base.canonicalPath) {
            navigateUp()
            return true
        }
        return false
    }

    private fun androidRoot(): File = Environment.getExternalStorageDirectory()
    private fun alpineRoot(): File = AlpineRootfs.rootDir(activity)
    private fun rootBase(): File = if (sdcardAlias || root == Root.ANDROID) androidRoot() else alpineRoot()

    private fun switchRoot(newRoot: Root) {
        onFileRootSelected()
        root = newRoot
        sdcardAlias = false
        navigateTo(rootBase())
    }

    private fun navigateTo(dir: File) {
        currentDir = dir
        reload()
    }

    private fun navigateUp() {
        if (sdcardAlias && currentDir.canonicalPath == androidRoot().canonicalPath) {
            sdcardAlias = false
            navigateTo(alpineRoot())
            return
        }
        val base = rootBase()
        if (currentDir.canonicalPath == base.canonicalPath) return
        currentDir.parentFile?.let { navigateTo(it) }
    }

    private var reloadTicket = 0

    private fun reload() {
        // listFiles + sort off the UI thread: the old sortedWith re-stat'ed isDirectory per
        // comparison (O(n log n) stats) and lowercased per comparison, janking large dirs.
        // One stat pass here, sorted on the snapshot; a ticket drops results if the user
        // navigated again while the listing was in flight.
        val dir = currentDir
        val ticket = ++reloadTicket
        ioExecutor.execute {
            val sorted = (dir.listFiles()?.toList() ?: emptyList())
                .map { it to (it.isDirectory to it.name.lowercase()) }
                .sortedWith(compareBy({ !(it.second.first) }, { it.second.second }))
                .map { it.first }
            val base = rootBase()
            val label = when {
                !base.isDirectory -> if (root == Root.ALPINE) "Alpine isn't set up yet — open the terminal once first." else "Not available"
                runCatching { dir.canonicalPath == base.canonicalPath }.getOrDefault(false) -> "/"
                else -> runCatching { dir.canonicalPath.removePrefix(base.canonicalPath) }.getOrDefault(dir.name)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || ticket != reloadTicket || dir != currentDir) return@runOnUiThread
                entries = sorted
                adapter.notifyDataSetChanged()
                pathText.text = label
            }
        }
    }

    private fun onRowTap(file: File) {
        if (!file.isDirectory) {
            viewFile(file)
            return
        }
        // Never follow directory symlinks: an Alpine absolute link resolves against the
        // host "/" (not the guest rebase), escaping the browser root with host parents
        // reachable via navigateUp afterwards.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            toast("Not entering link (points outside this folder)")
            return
        }
        if (root == Root.ALPINE && !sdcardAlias && runCatching { file.canonicalPath == File(alpineRoot(), "sdcard").canonicalPath }.getOrDefault(false)) {
            sdcardAlias = true
            navigateTo(androidRoot())
        } else {
            navigateTo(file)
        }
    }

    private fun iconResFor(file: File): Int = when {
        java.nio.file.Files.isSymbolicLink(file.toPath()) -> R.drawable.ic_link
        file.isDirectory -> R.drawable.ic_folder
        file.extension.equals("zip", ignoreCase = true) -> R.drawable.ic_folder_zip
        else -> R.drawable.ic_description
    }

    private fun iconTintFor(file: File): Int = when {
        java.nio.file.Files.isSymbolicLink(file.toPath()) -> 0xFF3ED0B8.toInt()
        file.isDirectory -> 0xFF7DA8FF.toInt()
        else -> 0xFF3ED0B8.toInt()
    }

    private fun subtitleFor(file: File): String {
        val modified = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified()))
        return if (file.isDirectory) modified else "${FileOps.humanSize(file.length())} • $modified"
    }

    // --- Per-item actions -----------------------------------------------------------------

    private fun showFileMenu(file: File, anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 0, 0, "Select (multi)")
        popup.menu.add(0, 1, 1, "View")
        popup.menu.add(0, 2, 2, "Open with…")
        popup.menu.add(0, 3, 3, "Copy")
        popup.menu.add(0, 4, 4, "Move")
        popup.menu.add(0, 5, 5, "Rename")
        popup.menu.add(0, 6, 6, "Delete")
        popup.menu.add(0, 7, 7, "Compress")
        if (file.extension.equals("zip", ignoreCase = true)) popup.menu.add(0, 8, 8, "Extract")
        popup.menu.add(0, 9, 9, "Share")
        popup.menu.add(0, 10, 10, "Info")
        if (guestPathFor(file) != null) popup.menu.add(0, 11, 11, "Open terminal here")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> toggleSelection(file)
                1 -> viewFile(file)
                2 -> openWith(file)
                3 -> { clipboard = Clipboard(listOf(file), cut = false); updateClipboardBar() }
                4 -> { clipboard = Clipboard(listOf(file), cut = true); updateClipboardBar() }
                5 -> renameDialog(file)
                6 -> deleteConfirm(file)
                7 -> compress(file)
                8 -> extract(file)
                9 -> shareFile(file)
                10 -> infoDialog(file)
                11 -> guestPathFor(file)?.let(onOpenTerminalHere)
            }
            true
        }
        popup.show()
    }

    /** Where this folder (or a file's parent) lives from inside the guest, or null if it isn't
     *  reachable there — the rootfs maps to "/", shared storage to /sdcard. */
    private fun guestPathFor(file: File): String? {
        val dir = if (file.isDirectory) file else file.parentFile ?: return null
        val p = dir.absolutePath
        val alpine = alpineRoot().absolutePath
        val android = androidRoot().absolutePath
        return when {
            p == alpine || p.startsWith("$alpine/") -> p.removePrefix(alpine).ifEmpty { "/" }
            p == android || p.startsWith("$android/") -> "/sdcard" + p.removePrefix(android)
            else -> null
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)

    /**
     * Symlinks are never opened/shared directly: a planted link (e.g. /sdcard/innocent.pdf
     * -> rootfs SSH key) would otherwise hand a third-party viewer the link TARGET with
     * no indication it isn't the file shown. The user can share the real file instead.
     */
    private fun requireRealFile(file: File): Boolean {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            toast("This is a link — open the real file instead")
            return false
        }
        return true
    }

    private fun mimeTypeFor(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    private fun viewFile(file: File) {
        if (file.isDirectory) { navigateTo(file); return }
        if (!requireRealFile(file)) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(file), mimeTypeFor(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        }.onFailure { toast("No app can open this file") }
    }

    private fun openWith(file: File) {
        if (!requireRealFile(file)) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(file), mimeTypeFor(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure { toast("No app can open this file") }
    }

    private fun shareFile(file: File) {
        if (!requireRealFile(file)) return
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(file)
                putExtra(Intent.EXTRA_STREAM, uriFor(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share"))
        }.onFailure { toast("Couldn't share this file") }
    }

    private fun renameDialog(file: File) {
        val input = EditText(activity).apply { setText(file.name); setSelection(0, file.name.length) }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                runCatching { FileOps.rename(file, input.text.toString().trim()) }
                    .onSuccess { reload() }
                    .onFailure { toast(it.message ?: "Rename failed") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteConfirm(file: File) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete \"${file.name}\"?")
            .setMessage(if (file.isDirectory) "This deletes the folder and everything inside it." else "This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                runInBackground({ FileOps.delete(file) }, { reload() }, { toast("Delete failed") })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun compress(file: File) {
        toast("Compressing…")
        val notifId = OperationNotifications.newId()
        OperationNotifications.progress(activity, notifId, "Compressing", file.name)
        runInBackground(
            {
                tracked(notifId, "Compressing") {
                    val destZip = File(file.parentFile, "${file.name}.zip")
                    if (destZip.exists()) throw IllegalStateException("${destZip.name} already exists")
                    FileOps.zip(file, destZip)
                }
            },
            { reload(); toast("Compressed") },
            { toast(it.message ?: "Compress failed") },
        )
    }

    private fun extract(file: File) {
        toast("Extracting…")
        val notifId = OperationNotifications.newId()
        OperationNotifications.progress(activity, notifId, "Extracting", file.name)
        runInBackground(
            {
                tracked(notifId, "Extracting") {
                    var lastUpdateMs = 0L
                    FileOps.unzip(file, file.parentFile!!) { count ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 300) {
                            lastUpdateMs = now
                            OperationNotifications.progress(activity, notifId, "Extracting", "$count entries extracted…")
                        }
                    }
                }
            },
            { reload(); toast("Extracted") },
            { toast(it.message ?: "Extract failed") },
        )
    }

    /** listFiles() on a huge directory is the one FS call in this whole per-item-action set that
     *  wasn't already routed off the UI thread — harmless for a normal folder, but a real jank/ANR
     *  risk for one with thousands of entries. */
    private fun infoDialog(file: File) {
        ioExecutor.execute {
            val perms = buildString {
                append(if (file.canRead()) "r" else "-")
                append(if (file.canWrite()) "w" else "-")
                append(if (file.canExecute()) "x" else "-")
            }
            val itemCount = if (file.isDirectory) file.listFiles()?.size ?: 0 else -1
            val message = buildString {
                append("Path: ${file.absolutePath}\n")
                append("Type: ${if (file.isDirectory) "Folder" else "File"}\n")
                if (itemCount >= 0) append("Items: $itemCount\n") else append("Size: ${FileOps.humanSize(file.length())}\n")
                append("Modified: ${DateFormat.getDateTimeInstance().format(Date(file.lastModified()))}\n")
                append("Permissions: $perms")
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                MaterialAlertDialogBuilder(activity)
                    .setTitle(file.name)
                    .setMessage(message)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    // --- Clipboard (copy/move, including across the two roots) ----------------------------

    private fun updateClipboardBar() {
        val c = clipboard
        if (c == null) {
            clipboardBar.visibility = View.GONE
        } else {
            clipboardBar.visibility = View.VISIBLE
            val label = if (c.files.size == 1) c.files[0].name else "${c.files.size} items"
            clipboardText.text = "${if (c.cut) "Move" else "Copy"}: $label"
        }
    }

    private fun pasteClipboard() {
        val c = clipboard ?: return
        val targetDir = currentDir
        val verb = if (c.cut) "Moving" else "Copying"
        val notifId = OperationNotifications.newId()
        OperationNotifications.progress(activity, notifId, verb, "0 / ${c.files.size}")
        runInBackground(
            {
                tracked(notifId, verb) {
                    c.files.forEachIndexed { index, file ->
                        if (c.cut) FileOps.move(file, targetDir) else FileOps.copy(file, targetDir)
                        OperationNotifications.progress(activity, notifId, verb, "${index + 1} / ${c.files.size}")
                    }
                }
            },
            {
                clipboard = null
                updateClipboardBar()
                reload()
            },
            { toast(it.message ?: "Paste failed") },
        )
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
