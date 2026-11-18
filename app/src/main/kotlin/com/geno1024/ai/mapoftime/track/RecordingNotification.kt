package com.geno1024.ai.mapoftime.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.geno1024.ai.mapoftime.MainActivity
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.ui.label

object RecordingNotification {
    private const val CHANNEL_ID = "recording"
    private const val NOTIFICATION_ID = 41

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_recording_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: RecordingState): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, TrackService::class.java).setAction(TrackService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mark = PendingIntent.getService(
            context,
            2,
            Intent(context, TrackService::class.java).setAction(TrackService.ACTION_MARK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = context.getString(
            R.string.notification_recording_text,
            formatDistance(state.distanceM),
            state.pointCount,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_recording_title, state.mode.label(context)))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_mark), mark)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    fun id(): Int = NOTIFICATION_ID

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else String.format("%.2f km", meters / 1000)
}
