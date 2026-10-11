package com.alpdroid.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keeps AlpBrowser's process at foreground priority once it is in use, so switching away to AlpDroid
 * (or any other app) to drive the agent does NOT freeze or kill AlpBrowser. Without this, Android
 * throttles/kills a plain background process — the proxy, the control-API bridge and the WebView's JS
 * timers would all stall the moment the user leaves AlpBrowser's Activity, defeating the entire point
 * of a browser the agent controls while the user is elsewhere.
 *
 * No-op besides existing: it holds no locks of its own (the browser doesn't need the CPU/Wi-Fi awake
 * the way long shell builds do — the proxy and bridge are plain sockets, cheap to leave open). Started
 * once the WebView is actually loaded (see BrowserScreen) and stopped from its own notification Stop
 * action; the user can always still force-stop the app from Android Settings.
 */
class BrowserKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground start made while backgrounded; never crash over it —
            // the process is just as killable as before, and the next time the app is foreground (or
            // the agent navigates/evals, waking the Activity) the caller retries.
            Log.w(TAG, "startForeground refused; will retry later", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AlpBrowser running", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps AlpBrowser reachable by the AlpDroid agent while you use other apps"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BrowserKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AlpBrowser is running")
            .setContentText("The agent can still control it — switch back to AlpDroid freely")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "AlpBrowser/KeepAlive"
        private const val CHANNEL_ID = "alpbrowser_keepalive"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.alpdroid.browser.STOP_KEEPALIVE"
    }
}
