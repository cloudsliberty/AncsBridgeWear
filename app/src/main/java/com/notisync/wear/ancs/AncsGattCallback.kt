package com.notisync.wear.ancs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** What kind of Control Point request the next Data Source stream is answering. */
private enum class PendingRequestKind { NOTIFICATION_ATTRIBUTES, APP_ATTRIBUTES }

private data class PendingRequest(
    val kind: PendingRequestKind,
    val notificationUid: Int,
    val appId: String,
    val command: ByteArray
)

/**
 * Handles the BLE GATT session with the iPhone's ANCS service: discovering the service,
 * subscribing to Notification Source / Data Source, and driving the Control Point request queue.
 *
 * ANCS answers Control Point requests strictly in the order they were written, one full response
 * per request, so a single request queue + a single reassembly buffer is sufficient — no need to
 * demultiplex interleaved responses.
 */
class AncsGattCallback(
    private val onSourceEvent: (AncsSourceEvent) -> Unit,
    private val onNotificationReady: (AncsNotification) -> Unit,
    private val onAppNameReady: (appId: String, displayName: String) -> Unit,
    private val onReady: (BluetoothGatt) -> Unit
) : BluetoothGattCallback() {

    companion object {
        private const val TAG = "AncsGattCallback"
        private const val TITLE_MAX = 64
        private const val SUBTITLE_MAX = 32
        private const val MESSAGE_MAX = 256
    }

    private var gatt: BluetoothGatt? = null
    private val requestQueue = ArrayDeque<PendingRequest>()
    private var awaitingResponse: PendingRequest? = null
    private val dataBuffer = mutableListOf<Byte>()

    @SuppressLint("MissingPermission") // Caller is responsible for the runtime permission check.
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "Connected to iPhone GATT server. Requesting MTU and discovering services...")
            this.gatt = gatt
            gatt.requestMtu(185) // Room for a full attribute TLV per ATT write; falls back gracefully if refused.
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.w(TAG, "Disconnected from iPhone (status=$status).")
            this.gatt = null
            requestQueue.clear()
            awaitingResponse = null
            dataBuffer.clear()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed (status=$status).")
            return
        }
        val ancsService = gatt.getService(Ancs.SERVICE_UUID)
        if (ancsService == null) {
            Log.e(TAG, "ANCS service not present — is this device actually an iPhone, and is it bonded?")
            return
        }
        Log.i(TAG, "ANCS service discovered. Subscribing to Notification Source and Data Source.")
        subscribe(gatt, ancsService.getCharacteristic(Ancs.NOTIFICATION_SOURCE_UUID))
        subscribe(gatt, ancsService.getCharacteristic(Ancs.DATA_SOURCE_UUID))
        onReady(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(Ancs.CLIENT_CONFIG_DESCRIPTOR_UUID) ?: return
        gatt.writeDescriptorCompat(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    // Deprecated single-arg overload — still the only one invoked on API < 33 (minSdk is 30), and
    // still invoked on newer API levels too, so this single override covers every supported device.
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val value = characteristic.value ?: return
        when (characteristic.uuid) {
            Ancs.NOTIFICATION_SOURCE_UUID -> handleSourceEvent(value)
            Ancs.DATA_SOURCE_UUID -> handleDataSourceChunk(value)
        }
    }

    private fun handleSourceEvent(bytes: ByteArray) {
        val event = AncsParser.parseSourceEvent(bytes) ?: return
        onSourceEvent(event)
        if (event.isNew || event.eventId == Ancs.EventId.MODIFIED) {
            enqueueNotificationAttributesRequest(event.notificationUid)
        }
    }

    private fun enqueueNotificationAttributesRequest(uid: Int) {
        val command = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(Ancs.CommandId.GET_NOTIFICATION_ATTRIBUTES)
            putInt(uid)
            put(Ancs.NotificationAttributeId.APP_IDENTIFIER)
            put(Ancs.NotificationAttributeId.TITLE); putShort(TITLE_MAX.toShort())
            put(Ancs.NotificationAttributeId.SUBTITLE); putShort(SUBTITLE_MAX.toShort())
            put(Ancs.NotificationAttributeId.MESSAGE); putShort(MESSAGE_MAX.toShort())
            put(Ancs.NotificationAttributeId.NEGATIVE_ACTION_LABEL)
        }.array()
        enqueue(PendingRequest(PendingRequestKind.NOTIFICATION_ATTRIBUTES, uid, appId = "", command = command))
    }

    fun requestAppName(appId: String) {
        if (appId.isBlank()) return
        val idBytes = appId.toByteArray(Charsets.UTF_8)
        val command = ByteBuffer.allocate(3 + idBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(Ancs.CommandId.GET_APP_ATTRIBUTES)
            put(idBytes)
            put(0) // NUL terminator required by the spec
            put(Ancs.AppAttributeId.DISPLAY_NAME)
        }.array()
        enqueue(PendingRequest(PendingRequestKind.APP_ATTRIBUTES, notificationUid = -1, appId = appId, command = command))
    }

    @SuppressLint("MissingPermission")
    fun performAction(notificationUid: Int, actionId: Byte) {
        val gatt = this.gatt ?: return
        val controlPoint = gatt.getService(Ancs.SERVICE_UUID)
            ?.getCharacteristic(Ancs.CONTROL_POINT_UUID) ?: return
        val command = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(Ancs.CommandId.PERFORM_NOTIFICATION_ACTION)
            putInt(notificationUid)
            put(actionId)
        }.array()
        gatt.writeCharacteristicCompat(controlPoint, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun enqueue(request: PendingRequest) {
        requestQueue.addLast(request)
        if (awaitingResponse == null) dispatchNext()
    }

    @SuppressLint("MissingPermission")
    private fun dispatchNext() {
        val gatt = this.gatt ?: return
        val next = requestQueue.pollFirst() ?: return
        val controlPoint = gatt.getService(Ancs.SERVICE_UUID)
            ?.getCharacteristic(Ancs.CONTROL_POINT_UUID) ?: return
        awaitingResponse = next
        dataBuffer.clear()
        gatt.writeCharacteristicCompat(controlPoint, next.command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun handleDataSourceChunk(chunk: ByteArray) {
        dataBuffer.addAll(chunk.toList())
        val pending = awaitingResponse ?: return
        val bytes = dataBuffer.toByteArray()

        when (pending.kind) {
            PendingRequestKind.NOTIFICATION_ATTRIBUTES -> {
                val notification = AncsParser.parseNotificationAttributes(bytes) ?: return
                completeCurrentRequestAndAdvance()
                onNotificationReady(notification)
            }
            PendingRequestKind.APP_ATTRIBUTES -> {
                val name = AncsParser.parseAppAttributes(bytes) ?: return
                completeCurrentRequestAndAdvance()
                onAppNameReady(pending.appId, name)
            }
        }
    }

    private fun completeCurrentRequestAndAdvance() {
        awaitingResponse = null
        dataBuffer.clear()
        dispatchNext()
    }
}
