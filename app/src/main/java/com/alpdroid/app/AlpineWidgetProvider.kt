package com.alpdroid.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * A one-tap home-screen launcher into a fresh session — for the same reason a CLI agent
 * (opencode/Claude Code/Cline) user wants a build finished-notification: getting back into a
 * running terminal, or starting one, without hunting for the app icon first.
 */
class AlpineWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_NEW_SESSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, id, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_alpdroid).apply {
                setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        const val ACTION_NEW_SESSION = "com.alpdroid.app.action.NEW_SESSION"
    }
}
