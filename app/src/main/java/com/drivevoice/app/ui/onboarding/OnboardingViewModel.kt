package com.drivevoice.app.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.drivevoice.app.service.MessageNotificationListener
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SetupStep {
    NOTIFICATION_ACCESS,
    MICROPHONE,
    BLUETOOTH,
    STATUS_NOTIFICATIONS,
    BATTERY;

    val runtimePermission: String?
        get() = when (this) {
            MICROPHONE -> Manifest.permission.RECORD_AUDIO
            BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else null
            STATUS_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else null
            NOTIFICATION_ACCESS, BATTERY -> null
        }
}

internal enum class StepStatus { GRANTED, NEEDED, UNAVAILABLE, NOT_REQUIRED }

internal data class SetupState(
    val notificationAccess: StepStatus,
    val microphone: StepStatus,
    val bluetooth: StepStatus,
    val statusNotifications: StepStatus,
    val battery: StepStatus,
) {
    val corePermissionsGranted: Boolean
        get() = notificationAccess == StepStatus.GRANTED && microphone == StepStatus.GRANTED &&
            bluetooth != StepStatus.NEEDED

    fun status(step: SetupStep): StepStatus = when (step) {
        SetupStep.NOTIFICATION_ACCESS -> notificationAccess
        SetupStep.MICROPHONE -> microphone
        SetupStep.BLUETOOTH -> bluetooth
        SetupStep.STATUS_NOTIFICATIONS -> statusNotifications
        SetupStep.BATTERY -> battery
    }
}

@HiltViewModel
internal class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val setup = MutableStateFlow(readSetup())
    val state = setup.asStateFlow()
    val listenerConnected = MessageNotificationListener.isConnected

    fun refresh() {
        setup.value = readSetup()
    }

    private fun readSetup(): SetupState {
        val manager = NotificationManagerCompat.from(context)
        return SetupState(
            notificationAccess = status(hasListenerAccess()),
            microphone = status(hasPermission(Manifest.permission.RECORD_AUDIO)),
            bluetooth = when {
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) ->
                    StepStatus.UNAVAILABLE
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> StepStatus.NOT_REQUIRED
                else -> status(hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
            },
            statusNotifications = status(manager.areNotificationsEnabled()),
            battery = status(
                context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
            ),
        )
    }

    private fun hasListenerAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val listener = ComponentName(context, MessageNotificationListener::class.java)
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listener)
    } else {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun status(granted: Boolean): StepStatus =
        if (granted) StepStatus.GRANTED else StepStatus.NEEDED
}
