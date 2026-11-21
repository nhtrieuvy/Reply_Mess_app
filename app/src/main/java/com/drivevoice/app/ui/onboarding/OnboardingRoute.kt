package com.drivevoice.app.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivevoice.app.R
import com.drivevoice.app.service.DriveForegroundService

@Composable
internal fun OnboardingRoute(activity: ComponentActivity, viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listenerConnected by viewModel.listenerConnected.collectAsStateWithLifecycle()
    val readingStatus by DriveForegroundService.readingStatus.collectAsStateWithLifecycle()
    var blockedSteps by rememberSaveable { mutableIntStateOf(0) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        for (step in SetupStep.entries) {
            val permission = step.runtimePermission ?: continue
            val granted = results[permission] ?: continue
            val flag = 1 shl step.ordinal
            val blocked = !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            blockedSteps = if (blocked) blockedSteps or flag else blockedSteps and flag.inv()
        }
        viewModel.refresh()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        for (step in SetupStep.entries) {
            if (viewModel.state.value.status(step) == StepStatus.GRANTED) {
                blockedSteps = blockedSteps and (1 shl step.ordinal).inv()
            }
        }
    }

    fun permissionToRequest(step: SetupStep): String? {
        val blocked = blockedSteps and (1 shl step.ordinal) != 0
        return if (blocked) null else step.runtimePermission
    }

    OnboardingScreen(
        state = state,
        listenerConnected = listenerConnected,
        readingStatus = readingStatus,
        onReadingToggle = {
            if (readingStatus.active) DriveForegroundService.stop(activity)
            else DriveForegroundService.start(activity)
        },
        actionLabel = { step ->
            if (permissionToRequest(step) == null) R.string.open_settings else R.string.grant_permission
        },
        onStepClick = { step ->
            val permission = permissionToRequest(step)
            if (permission != null) {
                permissionLauncher.launch(arrayOf(permission))
            } else {
                settingsUnavailable = !activity.openSetupSettings(step)
            }
        },
    )

    if (settingsUnavailable) {
        AlertDialog(
            onDismissRequest = { settingsUnavailable = false },
            text = { Text(stringResource(R.string.settings_unavailable)) },
            confirmButton = {
                TextButton(onClick = { settingsUnavailable = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

private fun ComponentActivity.openSetupSettings(step: SetupStep): Boolean {
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
    val primary = when (step) {
        SetupStep.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        SetupStep.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        SetupStep.STATUS_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        SetupStep.MICROPHONE, SetupStep.BLUETOOTH -> appDetails
    }
    val fallback = when (step) {
        SetupStep.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_SETTINGS)
        else -> appDetails
    }
    return listOf(primary, fallback).distinct().any { intent ->
        try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
