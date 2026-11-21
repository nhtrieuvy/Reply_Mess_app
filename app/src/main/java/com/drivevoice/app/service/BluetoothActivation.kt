package com.drivevoice.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.drivevoice.app.MainActivity
import com.drivevoice.app.R
import com.drivevoice.app.data.bluetooth.BluetoothDevices
import com.drivevoice.app.data.model.AppConfig
import com.drivevoice.app.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
internal class BluetoothActivation @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bluetooth: BluetoothDevices,
) {
    var activityVisible = false
    private var suppressedAddress: String? = null

    fun stop(config: AppConfig) {
        suppress(config.headsetAddress?.takeIf { config.autoStart })
        DriveForegroundService.stop(context)
    }

    fun suppress(address: String?) {
        if (address != null) suppressedAddress = address
    }

    fun disconnected(address: String) {
        if (suppressedAddress == address) suppressedAddress = null
    }

    fun connected(config: AppConfig, address: String, fromBroadcast: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !config.autoStart ||
            config.headsetAddress != address || config.sources.isEmpty() || suppressedAddress == address ||
            !bluetooth.hasPermission()
        ) return
        if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) return
        if (!fromBroadcast && !bluetooth.connected(address)) return
        if (!activityVisible) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (!notifications.areNotificationsEnabled() ||
                notifications.getNotificationChannel(DriveForegroundService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
            ) return
        }

        val replies = activityVisible && config.handsFree
        val started = DriveForegroundService.start(context, replies, config.autoSend, address, automatic = true)
        if (!started && fromBroadcast && !activityVisible) suggestOpening()
    }

    @SuppressLint("MissingPermission")
    private fun suggestOpening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = NotificationChannel("drive_connection", context.getString(R.string.settings_headset), NotificationManager.IMPORTANCE_LOW)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context, 24, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, "drive_connection")
                .setSmallIcon(R.drawable.ic_reading)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.drive_open_for_headset))
                .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build()
        try {
            manager.notify(24, notification)
        } catch (_: SecurityException) {
            // The user may revoke notifications between the permission check and posting.
        }
    }
}

@AndroidEntryPoint
class BluetoothConnectionReceiver : BroadcastReceiver() {
    @Inject internal lateinit var settings: SettingsRepository
    @Inject internal lateinit var bluetooth: BluetoothDevices
    @Inject internal lateinit var activation: BluetoothActivation

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED && action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED
        ) return
        val device = try {
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } catch (_: RuntimeException) {
            null
        } ?: return
        val address = device.address
        if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            activation.disconnected(address)
            bluetooth.refresh()
            return
        }
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED ||
            (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED) == BluetoothProfile.STATE_CONNECTED)
        if (!connected || !bluetooth.isPaired(device)) return
        bluetooth.refresh()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val config = withTimeoutOrNull(3_000) { settings.config.first() } ?: return@launch
                activation.connected(config, address, fromBroadcast = true)
            } finally {
                pending.finish()
            }
        }
    }
}
