package com.drivevoice.app.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

internal data class BluetoothDeviceModel(val address: String, val name: String)
internal data class BluetoothSnapshot(
    val paired: List<BluetoothDeviceModel>,
    val outputs: List<BluetoothDeviceModel>,
    val permissionGranted: Boolean,
)

internal val messageAudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

@Singleton
internal class BluetoothDevices @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val refreshSignal = MutableStateFlow(0)

    val snapshots = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { trySend(Unit) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { trySend(Unit) }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        trySend(Unit)
        awaitClose { audio.unregisterAudioDeviceCallback(callback) }
    }.combine(refreshSignal) { _, _ -> snapshot() }.distinctUntilChanged()

    fun refresh() { refreshSignal.update { it + 1 } }

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun snapshot(): BluetoothSnapshot {
        val permitted = hasPermission()
        val paired = if (permitted) {
            try {
                adapter?.bondedDevices.orEmpty().map { BluetoothDeviceModel(it.address, it.name.orEmpty()) }
                    .sortedBy { it.name.lowercase() }
            } catch (_: SecurityException) {
                emptyList()
            }
        } else emptyList()
        val outputs = if (permitted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isBluetoothMedia() }
                .mapNotNull { output ->
                    val address = output.address.takeIf(BluetoothAdapter::checkBluetoothAddress) ?: return@mapNotNull null
                    paired.firstOrNull { it.address == address } ?: BluetoothDeviceModel(address, output.productName.toString())
                }.distinctBy { it.address }
        } else emptyList()
        return BluetoothSnapshot(paired, outputs, permitted)
    }

    fun connected(address: String): Boolean = snapshot().outputs.any { it.address == address }

    @SuppressLint("MissingPermission")
    fun isPaired(device: BluetoothDevice): Boolean = hasPermission() && try {
        device.bondState == BluetoothDevice.BOND_BONDED
    } catch (_: SecurityException) {
        false
    }

    fun canPlayOn(address: String): Boolean {
        if (!connected(address)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val route = audio.getAudioDevicesForAttributes(messageAudioAttributes)
            return route.isNotEmpty() && route.all { it.isBluetoothMedia() && it.address == address }
        }
        return true
    }

    private fun AudioDeviceInfo.isBluetoothMedia(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
}
