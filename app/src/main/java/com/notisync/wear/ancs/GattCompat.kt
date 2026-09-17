package com.notisync.wear.ancs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build

/**
 * The typed, 3-argument write* overloads on [BluetoothGatt] were only added in API 33. minSdk for
 * this module is 30 (Wear OS 3.0), so every write goes through these compat shims instead of
 * calling the new APIs directly.
 */
@SuppressLint("MissingPermission", "DEPRECATION")
fun BluetoothGatt.writeCharacteristicCompat(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(characteristic, value, writeType) == BluetoothGatt.GATT_SUCCESS
    } else {
        characteristic.writeType = writeType
        characteristic.value = value
        writeCharacteristic(characteristic)
    }
}

@SuppressLint("MissingPermission", "DEPRECATION")
fun BluetoothGatt.writeDescriptorCompat(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
    } else {
        descriptor.value = value
        writeDescriptor(descriptor)
    }
}
