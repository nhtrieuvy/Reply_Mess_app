package com.drivevoice.app.ui.settings

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivevoice.app.R
import com.drivevoice.app.service.AudioCheckDevices
import com.drivevoice.app.service.AudioCheckIssue
import com.drivevoice.app.service.AudioCheckMode
import com.drivevoice.app.service.AudioCheckTrack
import com.drivevoice.app.service.AudioCheckPhase
import com.drivevoice.app.service.AudioEndpoint
import com.drivevoice.app.ui.theme.DrivePage
import kotlin.math.roundToInt

@Composable
internal fun AudioCheckScreen(
    viewModel: AudioCheckViewModel,
    driveActive: Boolean,
    micGranted: Boolean,
    bluetoothGranted: Boolean,
    bluetoothPermissionNeeded: Boolean,
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    onStopDrive: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deviceFlow = remember(viewModel, bluetoothGranted) { viewModel.devices(bluetoothGranted) }
    val devices by deviceFlow.collectAsStateWithLifecycle(AudioCheckDevices())
    LifecycleResumeEffect(viewModel) {
        viewModel.resume()
        onPauseOrDispose { viewModel.pause() }
    }

    DrivePage(stringResource(R.string.audio_check_title), onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AudioDevicePicker(
                R.string.audio_check_input, state.input, devices.inputs, state.actualInput,
                !state.active, viewModel::selectInput,
            )
            AudioDevicePicker(
                R.string.audio_check_output, state.output, devices.outputs, state.actualOutput,
                !state.active, viewModel::selectOutput,
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text(stringResource(R.string.audio_check_legacy_bluetooth), style = MaterialTheme.typography.bodySmall)
            }
            if (!micGranted || bluetoothPermissionNeeded) {
                Text(
                    stringResource(if (!micGranted) R.string.audio_check_mic_permission else R.string.audio_check_bluetooth_permission),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onPermissions, enabled = !state.active) { Text(stringResource(R.string.setup_title)) }
            }

            HorizontalDivider()

            Column {
                Text(stringResource(R.string.audio_check_volume, (state.volume * 100).roundToInt()))
                Slider(value = state.volume, onValueChange = viewModel::setVolume)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.audio_check_level), style = MaterialTheme.typography.labelLarge)
                MicLevel(state.level)
            }
            val statusLabel = when {
                driveActive || state.issue == AudioCheckIssue.BUSY -> R.string.audio_check_busy
                state.issue == AudioCheckIssue.PERMISSION -> R.string.audio_check_permission_error
                state.issue == AudioCheckIssue.MIC -> R.string.audio_check_mic_error
                state.issue == AudioCheckIssue.OUTPUT -> R.string.audio_check_output_error
                state.issue == AudioCheckIssue.DISCONNECTED -> R.string.audio_check_disconnected
                state.issue == AudioCheckIssue.FOCUS -> R.string.audio_check_focus_error
                state.issue == AudioCheckIssue.FILTER -> R.string.audio_check_filter_unavailable
                state.issue == AudioCheckIssue.UNCLEAR -> R.string.audio_check_unclear
                state.phase == AudioCheckPhase.OPENING_MIC -> R.string.audio_check_opening_mic
                state.phase == AudioCheckPhase.RECORDING -> R.string.audio_check_recording
                state.phase == AudioCheckPhase.OPENING_OUTPUT -> R.string.audio_check_opening_output
                state.phase == AudioCheckPhase.PLAYING -> R.string.audio_check_playing
                state.phase == AudioCheckPhase.COMPARING -> if (state.filteredAvailable) {
                    R.string.audio_check_compare_ready
                } else R.string.audio_check_filter_unavailable
                state.finished -> R.string.audio_check_finished
                else -> R.string.audio_check_ready
            }
            Text(
                stringResource(statusLabel),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.issue != null && state.issue != AudioCheckIssue.BUSY) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                driveActive -> OutlinedButton(onClick = onStopDrive, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.reading_stop))
                }
                state.active -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phase == AudioCheckPhase.RECORDING) {
                        Button(onClick = viewModel::finishRecording, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(stringResource(R.string.audio_check_play_now))
                        }
                    }
                    if (state.phase == AudioCheckPhase.COMPARING) {
                        if (state.impaired) {
                            Text(stringResource(R.string.audio_check_recording_impaired), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.play(AudioCheckTrack.RAW) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text(stringResource(R.string.audio_check_raw)) }
                            Button(
                                onClick = { viewModel.play(AudioCheckTrack.FILTERED) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                enabled = state.filteredAvailable,
                            ) { Text(stringResource(R.string.audio_check_filtered)) }
                        }
                    }
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.audio_check_stop))
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.start(AudioCheckMode.MICROPHONE) },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            enabled = micGranted,
                        ) { Text(stringResource(R.string.audio_check_test_mic)) }
                        OutlinedButton(
                            onClick = { viewModel.start(AudioCheckMode.SPEAKER) },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text(stringResource(R.string.audio_check_test_speaker)) }
                    }
                    OutlinedButton(
                        onClick = { viewModel.start(AudioCheckMode.SENTENCE) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = micGranted,
                    ) { Text(stringResource(R.string.audio_check_test_sentence)) }
                }
            }
        }
    }
}

@Composable
private fun AudioDevicePicker(
    label: Int,
    selected: AudioEndpoint?,
    options: List<AudioEndpoint>,
    actual: List<AudioEndpoint>,
    enabled: Boolean,
    onSelect: (AudioEndpoint?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val system = stringResource(R.string.audio_check_default)
    val selectedName = if (selected == null) system else endpointLabel(selected)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = enabled,
        ) {
            Text(if (selected != null && options.none { it.id == selected.id }) {
                stringResource(R.string.audio_check_device_missing, selectedName)
            } else selectedName, Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(system) }, onClick = { expanded = false; onSelect(null) })
            options.forEach { item ->
                DropdownMenuItem(text = { Text(endpointLabel(item)) }, onClick = { expanded = false; onSelect(item) })
            }
        }
        if (actual.isNotEmpty()) {
            val actualNames = actual.map { endpointLabel(it) }.joinToString(", ")
            Text(
                stringResource(R.string.audio_check_actual, actualNames),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun endpointLabel(endpoint: AudioEndpoint): String = when (endpoint.type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> stringResource(R.string.drive_mic_phone)
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> stringResource(R.string.audio_check_phone_speaker)
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> stringResource(R.string.audio_check_earpiece)
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> stringResource(R.string.audio_check_bluetooth_call, endpoint.name)
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> stringResource(R.string.audio_check_bluetooth_media, endpoint.name)
    else -> endpoint.name.ifBlank { stringResource(R.string.settings_headset_unnamed) }
}

@Composable
private fun MicLevel(level: Float) {
    val label = stringResource(R.string.audio_check_level)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier.fillMaxWidth().height(20.dp).semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
        },
    ) {
        val segment = size.width / 32
        val lit = (level * 32).roundToInt()
        repeat(32) { index ->
            val x = segment * (index + 0.5f)
            drawLine(if (index < lit) active else inactive, Offset(x, 3.dp.toPx()), Offset(x, size.height - 3.dp.toPx()),
                strokeWidth = segment * 0.55f, cap = StrokeCap.Round)
        }
    }
}
