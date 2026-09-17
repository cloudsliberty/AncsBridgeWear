package com.notisync.wear.ancs

import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) UUIDs and protocol constants, per Apple's public
 * spec: "Apple Notification Center Service (ANCS) Specification".
 *
 * ANCS lets any bonded BLE central (this watch) subscribe to notification events from an iPhone
 * and pull full title/message text for them, without an app running on the iPhone side. This is
 * the same mechanism third-party watches (e.g. Pebble) historically used.
 */
object Ancs {
    val SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")

    // Notifiable: fires on every new/updated/removed notification.
    val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")

    // Writeable: used to request full attributes for a notification, or perform an action on it.
    val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-8821-9E3502D685A9")

    // Notifiable: streams the (possibly multi-packet) response to a Control Point request.
    val DATA_SOURCE_UUID: UUID = UUID.fromString("22EA6770-010D-4CB5-A7DA-73867620129F")

    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** EventID values in a Notification Source (GATT notification) packet. */
    object EventId {
        const val ADDED: Byte = 0
        const val MODIFIED: Byte = 1
        const val REMOVED: Byte = 2
    }

    /** Bitmask values of the EventFlags byte in a Notification Source packet. */
    object EventFlags {
        const val SILENT: Int = 1 shl 0
        const val IMPORTANT: Int = 1 shl 1
        const val PRE_EXISTING: Int = 1 shl 2
        const val POSITIVE_ACTION: Int = 1 shl 3
        const val NEGATIVE_ACTION: Int = 1 shl 4
    }

    /** CommandID values written to the Control Point characteristic. */
    object CommandId {
        const val GET_NOTIFICATION_ATTRIBUTES: Byte = 0
        const val GET_APP_ATTRIBUTES: Byte = 1
        const val PERFORM_NOTIFICATION_ACTION: Byte = 2
    }

    /** NotificationAttributeID values used in a GetNotificationAttributes request/response. */
    object NotificationAttributeId {
        const val APP_IDENTIFIER: Byte = 0
        const val TITLE: Byte = 1
        const val SUBTITLE: Byte = 2
        const val MESSAGE: Byte = 3
        const val MESSAGE_SIZE: Byte = 4
        const val DATE: Byte = 5
        const val POSITIVE_ACTION_LABEL: Byte = 6
        const val NEGATIVE_ACTION_LABEL: Byte = 7
    }

    /** AppAttributeID values used in a GetAppAttributes request/response. */
    object AppAttributeId {
        const val DISPLAY_NAME: Byte = 0
    }

    /** ActionID values for PerformNotificationAction. */
    object ActionId {
        const val POSITIVE: Byte = 0
        const val NEGATIVE: Byte = 1
    }

    /** CategoryID values in a Notification Source packet (rough grouping only). */
    object CategoryId {
        const val OTHER: Byte = 0
        const val INCOMING_CALL: Byte = 1
        const val MISSED_CALL: Byte = 2
        const val VOICEMAIL: Byte = 3
        const val SOCIAL: Byte = 4
        const val SCHEDULE: Byte = 5
        const val EMAIL: Byte = 6
        const val NEWS: Byte = 7
        const val HEALTH_AND_FITNESS: Byte = 8
        const val BUSINESS_AND_FINANCE: Byte = 9
        const val LOCATION: Byte = 10
        const val ENTERTAINMENT: Byte = 11
    }
}

/** A parsed Notification Source GATT packet — the lightweight "something changed" event. */
data class AncsSourceEvent(
    val eventId: Byte,
    val eventFlags: Int,
    val categoryId: Byte,
    val categoryCount: Int,
    val notificationUid: Int
) {
    val isNew get() = eventId == Ancs.EventId.ADDED
    val isRemoved get() = eventId == Ancs.EventId.REMOVED
    val hasNegativeAction get() = eventFlags and Ancs.EventFlags.NEGATIVE_ACTION != 0
    val isImportant get() = eventFlags and Ancs.EventFlags.IMPORTANT != 0
    val isSilent get() = eventFlags and Ancs.EventFlags.SILENT != 0
}

/** The fully hydrated notification, assembled from a GetNotificationAttributes response. */
data class AncsNotification(
    val notificationUid: Int,
    val appId: String,
    val appDisplayName: String?,
    val title: String,
    val subtitle: String,
    val message: String,
    val categoryId: Byte,
    val supportsNegativeAction: Boolean
)
