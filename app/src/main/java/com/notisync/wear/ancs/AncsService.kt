package com.notisync.wear.ancs

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the BLE/GATT connection to the paired iPhone for the lifetime of
 * the mirroring session. Started (and its permission checks re-verified) from [MainActivity]
 * once a bonded device has been chosen.
 */
class AncsService : Service() {

    companion object {
        const val ACTION_START = "com.notisync.wear.ancs.action.START"
        const val ACTION_DISMISS_ON_PHONE = "com.notisync.wear.ancs.action.DISMISS_ON_PHONE"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        private const val SERVICE_CHANNEL_ID = "ancs_service_channel"
        private const val SERVICE_NOTIFICATION_ID = 1001
    }

    private lateinit var wearNotificationManager: WearNotificationManager
    private lateinit var pairingManager: BlePairingManager
    private var bluetoothGatt: BluetoothGatt? = null
    private var gattCallback: AncsGattCallback? = null
    private val appNameCache = mutableMapOf<String, String>()
    private val pendingByAppId = mutableMapOf<String, MutableList<AncsNotification>>()
    // AppID -> most recent source event flags, so we know Important/Silent by the time attributes arrive.
    private val sourceEventByUid = mutableMapOf<Int, AncsSourceEvent>()

    override fun onCreate() {
        super.onCreate()
        wearNotificationManager = WearNotificationManager(this)
        pairingManager = BlePairingManager(this)
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification("Waiting to connect..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address != null) connectTo(address)
            }
            ACTION_DISMISS_ON_PHONE -> {
                val uid = intent.getIntExtra(WearNotificationManager.EXTRA_NOTIFICATION_UID, -1)
                if (uid != -1) gattCallback?.performAction(uid, Ancs.ActionId.NEGATIVE)
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // Verified by MainActivity before this service is ever started.
    private fun connectTo(address: String) {
        val device: BluetoothDevice = pairingManager.deviceByAddress(address) ?: run {
            updateServiceNotification("Couldn't find paired device $address")
            return
        }
        bluetoothGatt?.close()

        gattCallback = AncsGattCallback(
            onSourceEvent = ::onSourceEvent,
            onNotificationReady = ::onNotificationReady,
            onAppNameReady = ::onAppNameReady,
            onReady = { updateServiceNotification("Mirroring notifications from ${device.name ?: address}") }
        )
        bluetoothGatt = device.connectGatt(this, /* autoConnect = */ true, gattCallback)
        updateServiceNotification("Connecting to ${device.name ?: address}...")
    }

    private fun onSourceEvent(event: AncsSourceEvent) {
        sourceEventByUid[event.notificationUid] = event
        if (event.isRemoved) {
            wearNotificationManager.cancel(event.notificationUid)
            sourceEventByUid.remove(event.notificationUid)
        }
    }

    private fun onNotificationReady(notification: AncsNotification) {
        val event = sourceEventByUid[notification.notificationUid]
        val cachedName = appNameCache[notification.appId]
        val hydrated = notification.copy(appDisplayName = cachedName)

        wearNotificationManager.post(
            hydrated,
            isImportant = event?.isImportant == true,
            isSilent = event?.isSilent == true
        )

        if (cachedName == null && notification.appId.isNotBlank()) {
            pendingByAppId.getOrPut(notification.appId) { mutableListOf() }.add(notification)
            gattCallback?.requestAppName(notification.appId)
        }
    }

    private fun onAppNameReady(appId: String, displayName: String) {
        appNameCache[appId] = displayName
        // Re-post any notifications from this app that arrived before we knew its display name.
        pendingByAppId.remove(appId)?.forEach { original ->
            wearNotificationManager.post(
                original.copy(appDisplayName = displayName),
                isImportant = sourceEventByUid[original.notificationUid]?.isImportant == true,
                isSilent = sourceEventByUid[original.notificationUid]?.isSilent == true
            )
        }
    }

    private fun buildServiceNotification(status: String): Notification {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "ANCS Bridge Status",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("iOS notification mirroring")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(status))
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
