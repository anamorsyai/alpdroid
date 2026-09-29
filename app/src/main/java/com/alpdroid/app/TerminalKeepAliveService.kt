package com.alpdroid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * A no-op-besides-existing foreground service: its only job is to hold this process at
 * foreground priority so Android is far less likely to kill it while a CLI coding agent
 * (opencode, Claude Code, Cline, ...) or a long build is running inside an Alpine session —
 * exactly the case a plain background app process gets reclaimed first. `proot` and the shells
 * it runs are child processes of this same app process, so keeping any one component of the
 * process foreground (this service) protects all of them equally; there's no need to move
 * session ownership into the service itself.
 *
 * Optionally also holds a partial wake lock (opt-in, since it costs real battery) so CPU-bound
 * background work keeps running with the screen off, not just avoids being killed outright.
 */
class TerminalKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && (application as AlpineTermApp).tabs.isEmpty()) {
            // A null intent means the whole process was killed and this service just got
            // restarted fresh by START_STICKY — proot and every shell were child processes of
            // that same dead process, so there is nothing left to actually keep alive. Without
            // this, the service just re-armed itself (and, if enabled, a real partial wake lock)
            // forever, showing "session running" indefinitely for sessions that no longer exist.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_EXIT_ALL) {
            // Destroying each session's underlying process here is enough on its own — every tab's
            // reader thread (started once by MainActivity, but living for as long as this process
            // does, independent of any particular Activity instance — the exact "outlives the
            // Activity" pattern this app already relies on for backgrounding) will see the pty hit
            // EOF right after and run the same tab.onExit → onTabExited() teardown a normal typed
            // "exit" already goes through, instead of this duplicating that bookkeeping itself and
            // risking getting it out of sync with the real one.
            (application as AlpineTermApp).tabs.forEach { it.session.destroy() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        // A null intent means Android restarted this service on its own after killing it
        // (guaranteed by START_STICKY) — not the caller saying "wake lock off". Falling back to
        // the persisted setting here, rather than treating a null intent as false, is what keeps
        // an opted-in wake lock actually held across that restart instead of silently dropping
        // the moment the OS reclaims the service under memory pressure, mid-build.
        val wakeLockWanted = intent?.getBooleanExtra(EXTRA_WAKE_LOCK, false) ?: SettingsStore(this).wakeLockEnabled
        if (wakeLockWanted) acquireWakeLock() else releaseWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlpDroid:cliAgent").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Background sessions", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps Alpine shell sessions and long-running commands alive in the background"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exitAll = PendingIntent.getService(
            this, 0, Intent(this, TerminalKeepAliveService::class.java).setAction(ACTION_EXIT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val tabCount = (application as AlpineTermApp).tabs.size
        val title = if (tabCount == 1) "1 session running" else "$tabCount sessions running"
        // NotificationCompat.Builder rather than the plain platform Notification.Builder(this,
        // channelId) two-arg constructor, which doesn't exist before API 26 (a NoSuchMethodError
        // waiting to happen on the API 24/25 devices this app's own minSdk claims to support) —
        // Compat's single implementation handles the pre-O fallback (no channel concept at all)
        // transparently instead.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Tap to return to your terminal")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitAll)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "alpineterm_keepalive"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_WAKE_LOCK = "wake_lock"
        private const val ACTION_EXIT_ALL = "com.alpdroid.app.EXIT_ALL_SESSIONS"
    }
}
