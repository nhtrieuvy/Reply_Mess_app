package com.drivevoice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.drivevoice.app.ui.onboarding.OnboardingRoute
import com.drivevoice.app.ui.onboarding.OnboardingViewModel
import com.drivevoice.app.ui.theme.DriveVoiceTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val onboardingViewModel by viewModels<OnboardingViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )

        setContent {
            DriveVoiceTheme {
                OnboardingRoute(this, onboardingViewModel)
            }
        }
    }
}
