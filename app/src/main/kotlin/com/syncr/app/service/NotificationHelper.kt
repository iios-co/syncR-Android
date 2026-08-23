package com.syncr.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.syncr.app.ui.SetupActivity

/**
 * Builds and updates the persistent ForegroundService notification.
 * Mirrors the activity log — every SyncState.log() call updates the notification.
 */
class NotificationHelper(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val tapIntent: PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, SetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    init { createChannel() }

    private fun createChannel() {
        NotificationChannel(CHANNEL_ID, "syncR Status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Real-time sync status"
            setShowBadge(false)
        }.also { manager.createNotificationChannel(it) }
    }

    fun buildInitial(): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("syncR")
            .setContentText("Watching for changes")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()

    /**
     * Update notification from a log entry.
     * Icon reflects the log level. Text shows the message.
     */
    fun showLogEntry(entry: SyncState.LogEntry) {
        val icon = when (entry.level) {
            SyncState.Level.OK    -> android.R.drawable.stat_notify_sync
            SyncState.Level.INFO  -> android.R.drawable.stat_notify_sync
            SyncState.Level.WARN  -> android.R.drawable.stat_notify_error
            SyncState.Level.ERROR -> android.R.drawable.stat_notify_error
        }
        val levelPrefix = when (entry.level) {
            SyncState.Level.OK    -> "✓"
            SyncState.Level.INFO  -> ""
            SyncState.Level.WARN  -> "⚠"
            SyncState.Level.ERROR -> "✕"
        }
        val baseText = if (levelPrefix.isNotEmpty()) "$levelPrefix ${entry.message}" else entry.message
        val text = "${entry.timeStr()} $baseText"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("syncR")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(icon)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Show a simple status message (no log entry). */
    fun showStatus(text: String, error: Boolean = false) {
        val icon = if (error) android.R.drawable.stat_notify_error else android.R.drawable.stat_notify_sync
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("syncR")
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "syncr_status"
        const val NOTIFICATION_ID = 1001
    }
}
