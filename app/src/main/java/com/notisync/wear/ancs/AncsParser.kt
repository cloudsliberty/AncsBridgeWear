package com.notisync.wear.ancs

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the two ANCS byte streams: the fixed-size Notification Source packet, and the
 * variable-length, TLV-encoded Data Source response to a Control Point request.
 */
object AncsParser {

    /** Notification Source is always exactly 8 bytes: EventID, EventFlags, CategoryID, CategoryCount, NotificationUID(4, LE). */
    fun parseSourceEvent(bytes: ByteArray): AncsSourceEvent? {
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eventId = buffer.get()
        val eventFlags = buffer.get().toInt() and 0xFF
        val categoryId = buffer.get()
        val categoryCount = buffer.get().toInt() and 0xFF
        val uid = buffer.int
        return AncsSourceEvent(eventId, eventFlags, categoryId, categoryCount, uid)
    }

    /**
     * Attempts to parse a complete GetNotificationAttributes response out of [bytes].
     * Returns null if the buffer doesn't yet hold a full response (more Data Source packets are
     * expected) — the caller should keep accumulating and retry.
     *
     * Response layout: CommandID(1) | NotificationUID(4, LE) | { AttributeID(1) | Length(2, LE) | Value(Length) }...
     */
    fun parseNotificationAttributes(bytes: ByteArray): AncsNotification? {
        if (bytes.size < 5) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val commandId = buffer.get()
        if (commandId != Ancs.CommandId.GET_NOTIFICATION_ATTRIBUTES) return null
        val uid = buffer.int

        var appId = ""
        var title = ""
        var subtitle = ""
        var message = ""
        var negativeActionLabelSeen = false

        while (buffer.remaining() >= 3) {
            val attributeId = buffer.get()
            val length = buffer.short.toInt() and 0xFFFF

            if (buffer.remaining() < length) {
                // Partial packet — wait for the rest of the Data Source stream.
                return null
            }
            val attrBytes = ByteArray(length)
            buffer.get(attrBytes)
            val value = String(attrBytes, Charsets.UTF_8)

            when (attributeId) {
                Ancs.NotificationAttributeId.APP_IDENTIFIER -> appId = value
                Ancs.NotificationAttributeId.TITLE -> title = value
                Ancs.NotificationAttributeId.SUBTITLE -> subtitle = value
                Ancs.NotificationAttributeId.MESSAGE -> message = value
                Ancs.NotificationAttributeId.NEGATIVE_ACTION_LABEL -> negativeActionLabelSeen = true
                // DATE / MESSAGE_SIZE / POSITIVE_ACTION_LABEL intentionally not surfaced yet.
            }
        }

        return AncsNotification(
            notificationUid = uid,
            appId = appId,
            appDisplayName = null, // filled in by AncsService from a GetAppAttributes lookup, if cached
            title = title.ifBlank { appId.ifBlank { "Notification" } },
            subtitle = subtitle,
            message = message,
            categoryId = Ancs.CategoryId.OTHER,
            supportsNegativeAction = negativeActionLabelSeen
        )
    }

    /**
     * Response layout for GetAppAttributes: CommandID(1) | AppIdentifier(variable, NUL-terminated)
     * | { AttributeID(1) | Length(2, LE) | Value(Length) }...
     * Returns the display name, or null if the buffer isn't complete yet.
     */
    fun parseAppAttributes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val nulIndex = bytes.indexOf(0.toByte(), startIndex = 1)
        if (nulIndex < 0) return null // App identifier string not yet fully received.

        val buffer = ByteBuffer.wrap(bytes, nulIndex + 1, bytes.size - nulIndex - 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 3) {
            val attributeId = buffer.get()
            val length = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < length) return null
            val attrBytes = ByteArray(length)
            buffer.get(attrBytes)
            if (attributeId == Ancs.AppAttributeId.DISPLAY_NAME) {
                return String(attrBytes, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun ByteArray.indexOf(byte: Byte, startIndex: Int): Int {
        for (i in startIndex until size) if (this[i] == byte) return i
        return -1
    }
}
