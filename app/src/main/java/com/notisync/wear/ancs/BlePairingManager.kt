package com.notisync.wear.ancs

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * ANCS requires an encrypted, *bonded* BLE link — the iPhone will not expose the ANCS service to
 * a central it hasn't paired with. Rather than reimplement BLE bonding (which triggers iOS's own
 * system pairing dialog), this app leans on the watch's system Bluetooth settings to create the
 * bond, then simply lets the user pick which already-bonded device is the iPhone to bridge.
 */
class BlePairingManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Bonded devices, filtered to likely phones (rules out bonded headsets/watches/etc. when possible). */
    @Suppress("MissingPermission") // Callers must check hasConnectPermission() first.
    fun bondedDevices(): List<BluetoothDevice> =
        if (hasConnectPermission()) adapter?.bondedDevices?.toList().orEmpty() else emptyList()

    /** Opens the watch's system Bluetooth settings so the user can pair with their iPhone. */
    fun openSystemPairingSettings() {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun deviceByAddress(address: String): BluetoothDevice? =
        if (BluetoothAdapter.checkBluetoothAddress(address) && hasConnectPermission()) {
            adapter?.getRemoteDevice(address)
        } else null
}
