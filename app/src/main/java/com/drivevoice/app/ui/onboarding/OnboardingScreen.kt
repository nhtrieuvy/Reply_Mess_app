package com.drivevoice.app.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivevoice.app.R
import com.drivevoice.app.ui.theme.DrivePage

@Composable
internal fun OnboardingScreen(
    state: SetupState,
    listenerConnected: Boolean,
    onBack: () -> Unit,
    actionLabel: (SetupStep) -> Int,
    onStepClick: (SetupStep) -> Unit,
) {
    DrivePage(stringResource(R.string.setup_title), onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setup_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                AnimatedStatus(
                    label = if (state.corePermissionsGranted) R.string.setup_complete else R.string.setup_incomplete,
                    positive = state.corePermissionsGranted,
                    uppercase = true,
                )
            }

            for (step in SetupStep.entries) {
                val status = state.status(step)
                HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.12f))
                SetupRow(
                    title = step.title,
                    description = if (step == SetupStep.BLUETOOTH && status == StepStatus.UNAVAILABLE) {
                        R.string.bluetooth_unavailable
                    } else {
                        step.description
                    },
                    statusLabel = step.statusLabel(status, listenerConnected),
                    positive = status == StepStatus.GRANTED,
                    showAccessInstructions = step == SetupStep.NOTIFICATION_ACCESS && status == StepStatus.NEEDED,
                    actionLabel = if (status == StepStatus.NEEDED) actionLabel(step) else null,
                    onAction = { onStepClick(step) },
                )
            }
        }
    }
}

@Composable
private fun SetupRow(
    @StringRes title: Int,
    @StringRes description: Int,
    @StringRes statusLabel: Int,
    positive: Boolean,
    showAccessInstructions: Boolean,
    @StringRes actionLabel: Int?,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (showAccessInstructions) {
            Text(
                text = stringResource(R.string.access_instructions),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AnimatedStatus(label = statusLabel, positive = positive)
        if (actionLabel != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(text = stringResource(actionLabel), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun AnimatedStatus(@StringRes label: Int, positive: Boolean, uppercase: Boolean = false) {
    AnimatedContent(
        targetState = label,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        transitionSpec = {
            (slideInVertically { it / 4 } + fadeIn()) togetherWith
                (slideOutVertically { -it / 4 } + fadeOut())
        },
        label = "Permission status",
    ) { currentLabel ->
        Text(
            text = stringResource(currentLabel),
            color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = if (uppercase) 2.sp else 0.1.sp,
        )
    }
}

private val SetupStep.title: Int
    get() = when (this) {
        SetupStep.NOTIFICATION_ACCESS -> R.string.access_title
        SetupStep.MICROPHONE -> R.string.microphone_title
        SetupStep.BLUETOOTH -> R.string.bluetooth_title
        SetupStep.STATUS_NOTIFICATIONS -> R.string.status_notifications_title
        SetupStep.BATTERY -> R.string.battery_title
    }

private val SetupStep.description: Int
    get() = when (this) {
        SetupStep.NOTIFICATION_ACCESS -> R.string.access_description
        SetupStep.MICROPHONE -> R.string.microphone_description
        SetupStep.BLUETOOTH -> R.string.bluetooth_description
        SetupStep.STATUS_NOTIFICATIONS -> R.string.status_notifications_description
        SetupStep.BATTERY -> R.string.battery_description
    }

private fun SetupStep.statusLabel(status: StepStatus, listenerConnected: Boolean): Int = when {
    this == SetupStep.BATTERY && status == StepStatus.GRANTED -> R.string.battery_exempt
    this == SetupStep.BATTERY -> R.string.battery_optimized
    this == SetupStep.NOTIFICATION_ACCESS && status == StepStatus.GRANTED ->
        if (listenerConnected) R.string.access_connected else R.string.access_waiting
    else -> when (status) {
        StepStatus.GRANTED -> R.string.granted
        StepStatus.NEEDED -> R.string.needed
        StepStatus.UNAVAILABLE -> R.string.unavailable
        StepStatus.NOT_REQUIRED -> R.string.not_required
    }
}

