package com.notisync.wear.ancs

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {

    private lateinit var pairingManager: BlePairingManager
    private val prefs by lazy { getSharedPreferences("ancs_bridge", MODE_PRIVATE) }

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingManager = BlePairingManager(this)

        setContent {
            var permissionsGranted by remember { mutableStateOf(hasAllPermissions()) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result -> permissionsGranted = result.values.all { it } }

            var selectedAddress by remember {
                mutableStateOf(prefs.getString(KEY_DEVICE_ADDRESS, null))
            }

            BridgeScreen(
                permissionsGranted = permissionsGranted,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                bondedDevices = if (permissionsGranted) pairingManager.bondedDevices() else emptyList(),
                selectedAddress = selectedAddress,
                onOpenSystemPairing = { pairingManager.openSystemPairingSettings() },
                onSelectDevice = { device ->
                    selectedAddress = device.address
                    prefs.edit().putString(KEY_DEVICE_ADDRESS, device.address).apply()
                    startMirroring(device.address)
                }
            )
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startMirroring(address: String) {
        val intent = Intent(this, AncsService::class.java).apply {
            action = AncsService.ACTION_START
            putExtra(AncsService.EXTRA_DEVICE_ADDRESS, address)
        }
        startForegroundService(intent)
    }

    companion object {
        private const val KEY_DEVICE_ADDRESS = "device_address"
    }
}

@Composable
private fun BridgeScreen(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    bondedDevices: List<BluetoothDevice>,
    selectedAddress: String?,
    onOpenSystemPairing: () -> Unit,
    onSelectDevice: (BluetoothDevice) -> Unit
) {
    Scaffold {
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "ANCS Bridge",
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (!permissionsGranted) {
                item {
                    Text(
                        text = "Bluetooth & notification permissions are required to mirror your iPhone.",
                        style = MaterialTheme.typography.caption2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                item {
                    Button(onClick = onRequestPermissions) { Text("Grant permissions") }
                }
                return@ScalingLazyColumn
            }

            item {
                Text(
                    text = "Pair your iPhone in system Bluetooth settings first, then pick it below.",
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            item {
                Button(onClick = onOpenSystemPairing) { Text("Open Bluetooth settings") }
            }

            if (bondedDevices.isEmpty()) {
                item {
                    Text(
                        text = "No bonded devices found yet.",
                        style = MaterialTheme.typography.caption2
                    )
                }
            } else {
                items(bondedDevices) { device ->
                    val isSelected = device.address == selectedAddress
                    Chip(
                        onClick = { onSelectDevice(device) },
                        label = { Text(device.name ?: device.address) },
                        secondaryLabel = { Text(if (isSelected) "Mirroring" else "Tap to mirror this device") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}
