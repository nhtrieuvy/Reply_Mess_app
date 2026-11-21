package com.drivevoice.app

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.ActivityNotFoundException
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivevoice.app.data.model.AppConfig
import com.drivevoice.app.service.replayAllowed
import com.drivevoice.app.service.ReadingStatus
import com.drivevoice.app.service.DriveForegroundService
import com.drivevoice.app.ui.home.DriveHomeScreen
import com.drivevoice.app.ui.home.DriveViewModel
import com.drivevoice.app.ui.onboarding.OnboardingRoute
import com.drivevoice.app.ui.onboarding.OnboardingViewModel
import com.drivevoice.app.ui.onboarding.StepStatus
import com.drivevoice.app.ui.settings.DriveSettingsScreen
import com.drivevoice.app.ui.theme.DriveVoiceTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val onboardingViewModel by viewModels<OnboardingViewModel>()
    private val driveViewModel by viewModels<DriveViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )

        setContent {
            DriveVoiceTheme {
                DriveApp()
            }
        }
    }

    @Composable
    private fun DriveApp() {
        var screen by rememberSaveable { mutableStateOf(DriveScreen.HOME) }
        var permissionsBack by rememberSaveable { mutableStateOf(DriveScreen.HOME) }
        var visible by remember { mutableStateOf(false) }
        val config by driveViewModel.config.collectAsStateWithLifecycle()
        val devices by driveViewModel.devices.collectAsStateWithLifecycle()
        val status by driveViewModel.status.collectAsStateWithLifecycle()
        val sender by driveViewModel.sender.collectAsStateWithLifecycle()
        val meter by driveViewModel.meter.collectAsStateWithLifecycle()
        val issue by driveViewModel.settingsIssue.collectAsStateWithLifecycle()
        val permissions by onboardingViewModel.state.collectAsStateWithLifecycle()
        val listener by onboardingViewModel.listenerConnected.collectAsStateWithLifecycle()
        val preferences = config ?: AppConfig(sources = emptySet())

        LifecycleResumeEffect(Unit) {
            driveViewModel.setVisible(true)
            onboardingViewModel.refresh()
            visible = true
            onPauseOrDispose {
                visible = false
                driveViewModel.setVisible(false)
            }
        }
        LaunchedEffect(visible, config, devices, listener) {
            if (visible && listener && config != null) driveViewModel.tryAutomaticStart(preferences, devices)
        }

        fun showPermissions() {
            permissionsBack = screen
            screen = DriveScreen.PERMISSIONS
        }
        fun back() {
            screen = if (screen == DriveScreen.PERMISSIONS) permissionsBack else DriveScreen.HOME
        }
        BackHandler(enabled = screen != DriveScreen.HOME) { back() }
        when (screen) {
            DriveScreen.HOME -> {
                val output = devices.outputs.firstOrNull { it.address == preferences.headsetAddress }
                    ?: devices.outputs.firstOrNull()
                DriveHomeScreen(
                    deviceName = output?.name?.ifBlank { stringResource(R.string.settings_headset_unnamed) },
                    listenerConnected = listener,
                    hasSources = preferences.sources.isNotEmpty(),
                    status = status,
                    sender = sender,
                    meter = meter,
                    onSettings = { screen = DriveScreen.SETTINGS },
                    onAction = {
                        when {
                            status.replayAllowed && sender != null -> driveViewModel.replay()
                            status.active -> driveViewModel.stop(preferences)
                            !listener -> showPermissions()
                            preferences.sources.isEmpty() -> screen = DriveScreen.SETTINGS
                            status == ReadingStatus.NO_OFFLINE_VOICE -> {
                                DriveForegroundService.dismissVoiceError()
                                try {
                                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                                } catch (_: ActivityNotFoundException) {
                                    startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                            }
                            else -> driveViewModel.start(preferences)
                        }
                    },
                )
            }
            DriveScreen.SETTINGS -> DriveSettingsScreen(
                preferences, devices, status,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && permissions.microphone == StepStatus.GRANTED,
                issue, driveViewModel, ::back, ::showPermissions,
                onPair = {
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        showPermissions()
                    }
                },
            )
            DriveScreen.PERMISSIONS -> OnboardingRoute(this, onboardingViewModel, ::back)
        }
    }
}

private enum class DriveScreen { HOME, SETTINGS, PERMISSIONS }
