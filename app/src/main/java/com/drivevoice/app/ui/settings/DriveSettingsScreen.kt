package com.drivevoice.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.drivevoice.app.R
import com.drivevoice.app.data.bluetooth.BluetoothSnapshot
import com.drivevoice.app.data.model.AppConfig
import com.drivevoice.app.data.model.MessageApp
import com.drivevoice.app.service.ReadingStatus
import com.drivevoice.app.ui.home.DriveViewModel
import com.drivevoice.app.ui.home.SettingsIssue
import com.drivevoice.app.ui.theme.DrivePage

@Composable
internal fun DriveSettingsScreen(
    config: AppConfig,
    devices: BluetoothSnapshot,
    status: ReadingStatus,
    canReply: Boolean,
    issue: SettingsIssue?,
    viewModel: DriveViewModel,
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    onPair: () -> Unit,
) {
    var addingVip by rememberSaveable { mutableStateOf(false) }
    DrivePage(stringResource(R.string.drive_settings), onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
            SettingsHeading(R.string.settings_headset)
            SettingsHint(R.string.settings_background_read_only)
            if (!devices.permissionGranted) {
                TextButton(onClick = onPermissions) { Text(stringResource(R.string.drive_enable_access)) }
            } else {
                Column(Modifier.selectableGroup()) {
                    DeviceOption(stringResource(R.string.settings_headset_none), config.headsetAddress == null) {
                        viewModel.selectHeadset(null)
                    }
                    for (device in devices.paired) {
                        val connected = devices.outputs.any { it.address == device.address }
                        val name = device.name.ifBlank { stringResource(R.string.settings_headset_unnamed) }
                        DeviceOption(
                            if (connected) stringResource(R.string.settings_connected_device, name) else name,
                            config.headsetAddress == device.address,
                        ) { viewModel.selectHeadset(device.address) }
                    }
                }
                if (config.headsetAddress != null && devices.paired.none { it.address == config.headsetAddress }) {
                    SettingsHint(R.string.settings_headset_missing)
                }
                TextButton(onClick = onPair) { Text(stringResource(R.string.settings_pair)) }
            }
            SettingSwitch(
                stringResource(R.string.settings_auto_start), config.autoStart,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && devices.permissionGranted &&
                    devices.paired.any { it.address == config.headsetAddress },
            ) { viewModel.setAutoStart(it) }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) SettingsHint(R.string.settings_auto_version)

            SettingsDivider()
            SettingsHeading(R.string.settings_sources)
            for (app in MessageApp.entries) {
                SettingSwitch(app.label, app in config.sources) { viewModel.setSource(app, it) }
            }
            if (config.sources.isEmpty()) SettingsHint(R.string.settings_source_empty)

            SettingsDivider()
            SettingsHeading(R.string.settings_vip)
            SettingsHint(R.string.settings_vip_hint)
            SettingSwitch(stringResource(R.string.settings_vip_only), config.vipOnly) { viewModel.setVipOnly(it) }
            for (contact in config.vips.sortedWith(compareBy({ it.app.label }, { it.name.lowercase() }))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_vip_label, contact.name, contact.app.label), Modifier.weight(1f))
                    TextButton(onClick = { viewModel.removeVip(contact) }) { Text(stringResource(R.string.settings_remove)) }
                }
            }
            if (config.vipOnly && config.vips.isEmpty()) SettingsHint(R.string.settings_vip_empty)
            TextButton(onClick = { addingVip = true }) { Text(stringResource(R.string.settings_add_vip)) }

            SettingsDivider()
            SettingsHeading(R.string.settings_reply)
            SettingSwitch(stringResource(R.string.reply_enable), config.handsFree, canReply && !status.active) {
                viewModel.setHandsFree(it)
            }
            if (!canReply) SettingsHint(R.string.reply_requirements)
            if (config.handsFree) {
                SettingSwitch(stringResource(R.string.reply_auto_send), config.autoSend, canReply && !status.active) {
                    viewModel.setAutoSend(it)
                }
            }
            if (status.active) {
                TextButton(onClick = { viewModel.stop(config) }) { Text(stringResource(R.string.reading_stop)) }
            }
            SettingsDivider()
            TextButton(onClick = onPermissions) { Text(stringResource(R.string.setup_title)) }
        }
    }

    if (addingVip) {
        VipDialog(
            invalid = issue == SettingsIssue.INVALID_VIP,
            onClose = {
                addingVip = false
                viewModel.dismissIssue()
            },
            onChange = viewModel::dismissIssue,
            onAdd = { app, name -> viewModel.addVip(app, name) { addingVip = false } },
        )
    }
    if (issue == SettingsIssue.STORAGE) {
        AlertDialog(
            onDismissRequest = viewModel::dismissIssue,
            text = { Text(stringResource(R.string.settings_storage_error)) },
            confirmButton = { TextButton(onClick = viewModel::dismissIssue) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun VipDialog(
    invalid: Boolean,
    onClose: () -> Unit,
    onChange: () -> Unit,
    onAdd: (MessageApp, String) -> Unit,
) {
    var app by rememberSaveable { mutableStateOf(MessageApp.ZALO) }
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.settings_add_vip)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(96); onChange() },
                    label = { Text(stringResource(R.string.settings_sender_name)) },
                    singleLine = true, isError = invalid,
                )
                if (invalid) SettingsHint(R.string.settings_vip_invalid)
                Column(Modifier.selectableGroup()) {
                    for (source in MessageApp.entries) {
                        DeviceOption(source.label, app == source) { app = source; onChange() }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onAdd(app, name) }) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, Modifier.weight(1f).padding(end = 16.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun DeviceOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun SettingsHeading(label: Int) {
    Text(stringResource(label), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun SettingsHint(label: Int) {
    Text(
        stringResource(label), Modifier.padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color.White.copy(alpha = 0.12f))
}
