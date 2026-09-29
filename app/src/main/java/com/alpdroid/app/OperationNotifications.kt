package com.alpdroid.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ongoing-progress notifications for file operations long enough to lose track of while the app
 * is backgrounded or the drawer is closed — copy/move/compress/extract in [files.FileBrowserPanel],
 * backup/restore in [MainActivity]. Separate from [TerminalKeepAliveService]'s own notification:
 * that one means "a shell is alive," these mean "this one operation is still running," with their
 * own start/progress/end instead of tracking live session count indefinitely.
 */
object OperationNotifications {
    private const val CHANNEL_ID = "alpdroid_operations"
    private val nextId = AtomicInteger(9000)

    /** A fresh notification id for one operation's whole lifetime (update calls then one finish/
     *  cancel) — callers hold onto it rather than the notifier tracking operations by name, since
     *  two of the same kind (two backups queued back to back) are still two separate progresses. */
    fun newId(): Int = nextId.incrementAndGet()

    private fun ensureChannel(context: Context): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "File operations", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress for copy, move, compress, extract, backup and restore"
                    setShowBadge(false)
                },
            )
        }
        return nm
    }

    // POST_NOTIFICATIONS is a runtime permission on API 33+ (MainActivity already requests it
    // for the keep-alive notification) — notify() throws SecurityException without it rather
    // than silently no-op'ing, which would otherwise crash a background copy/backup thread over
    // something that's purely cosmetic progress feedback.
    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Same pattern as TerminalKeepAliveService's own "tap to open" action — a plain launch
    // Intent rather than one carrying which operation/tab this was for, since MainActivity
    // already resumes wherever the user left it (the drawer, the active tab) with no extra
    // state to thread through here.
    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Shows/updates an ongoing, non-dismissible progress notification. Omitting [max] (leaving
     *  it 0) renders an indeterminate spinner, for operations with no known item/byte count up
     *  front (a single compress/extract call, a copy still walking a directory tree). */
    fun progress(context: Context, id: Int, title: String, text: String, current: Int = 0, max: Int = 0) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .setProgress(max, current, max == 0)
            .build()
        runCatching { ensureChannel(context).notify(id, notification) }
    }

    /** Replaces the ongoing notification with a final, dismissible result — auto-cancels itself
     *  once tapped, since there's nothing further to show once an operation is done. */
    fun finish(context: Context, id: Int, title: String, text: String, success: Boolean) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching { ensureChannel(context).notify(id, notification) }
    }

    /** Operations never survive a process death, but their notifications do — a backup killed
     *  mid-run would otherwise leave "Backing up Alpine…" stuck in the shade forever. Called once
     *  per process start, before any new operation can post its own. */
    fun clearStale(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.activeNotifications
                .filter { it.id > 9000 && it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 }
                .forEach { nm.cancel(it.id) }
        }
    }
}
