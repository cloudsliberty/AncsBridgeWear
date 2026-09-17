package com.notisync.wear.ancs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when the user swipes away a mirrored notification on the watch. Forwards a
 * PerformNotificationAction(Negative) command to [AncsService] so the dismissal is relayed back
 * to the iPhone — this is what keeps both sides in sync instead of the watch silently diverging.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WearNotificationManager.ACTION_DISMISS) return
        val uid = intent.getIntExtra(WearNotificationManager.EXTRA_NOTIFICATION_UID, -1)
        if (uid == -1) return

        val serviceIntent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_DISMISS_ON_PHONE
            putExtra(WearNotificationManager.EXTRA_NOTIFICATION_UID, uid)
        }
        context.startService(serviceIntent)
    }
}
