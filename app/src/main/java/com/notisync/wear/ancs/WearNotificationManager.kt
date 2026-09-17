package com.notisync.wear.ancs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Renders mirrored ANCS notifications as ordinary watch notifications. */
class WearNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_DEFAULT = "ios_mirrored_default"
        const val CHANNEL_IMPORTANT = "ios_mirrored_important"
        const val CHANNEL_SILENT = "ios_mirrored_silent"

        const val ACTION_DISMISS = "com.notisync.wear.ancs.action.DISMISS"
        const val EXTRA_NOTIFICATION_UID = "extra_notification_uid"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel(CHANNEL_IMPORTANT, "iOS Alerts (Important)", NotificationManager.IMPORTANCE_HIGH)
        createChannel(CHANNEL_DEFAULT, "iOS Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        createChannel(CHANNEL_SILENT, "iOS Alerts (Silent)", NotificationManager.IMPORTANCE_LOW)
    }

    private fun createChannel(id: String, name: String, importance: Int) {
        val channel = NotificationChannel(id, name, importance).apply {
            description = "Notifications mirrored from your iPhone over Bluetooth ANCS"
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun post(notification: AncsNotification, isImportant: Boolean, isSilent: Boolean) {
        val channelId = when {
            isImportant -> CHANNEL_IMPORTANT
            isSilent -> CHANNEL_SILENT
            else -> CHANNEL_DEFAULT
        }
        val appLabel = notification.appDisplayName ?: notification.appId.substringAfterLast('.')

        val deleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_NOTIFICATION_UID, notification.notificationUid)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            notification.notificationUid,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setSubText(appLabel.ifBlank { null })
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setDeleteIntent(deletePendingIntent) // dismissing on the watch relays the dismiss to the iPhone
            .setPriority(
                if (isImportant) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )

        if (notification.subtitle.isNotBlank()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${notification.subtitle}\n${notification.message}")
            )
        }

        notificationManager.notify(notification.notificationUid, builder.build())
    }

    /** Called on an ANCS "Removed" event — the iPhone dismissed or the user handled it there. */
    fun cancel(notificationUid: Int) {
        notificationManager.cancel(notificationUid)
    }
}
