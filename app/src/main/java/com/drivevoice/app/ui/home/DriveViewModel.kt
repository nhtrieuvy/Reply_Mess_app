package com.drivevoice.app.ui.home

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivevoice.app.data.bluetooth.BluetoothDevices
import com.drivevoice.app.data.bluetooth.BluetoothSnapshot
import com.drivevoice.app.data.model.AppConfig
import com.drivevoice.app.data.model.MessageApp
import com.drivevoice.app.data.model.VipContact
import com.drivevoice.app.data.repository.SettingsRepository
import com.drivevoice.app.service.BluetoothActivation
import com.drivevoice.app.service.DriveForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
internal class DriveViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val bluetooth: BluetoothDevices,
    private val activation: BluetoothActivation,
) : ViewModel() {
    val config = settings.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val devices = bluetooth.snapshots.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        BluetoothSnapshot(emptyList(), emptyList(), bluetooth.hasPermission()),
    )
    val status = DriveForegroundService.readingStatus
    val sender = DriveForegroundService.latestSender
    val meter = DriveForegroundService.voiceMeter
    private val issue = MutableStateFlow<SettingsIssue?>(null)
    val settingsIssue = issue.asStateFlow()

    fun setVisible(visible: Boolean) {
        activation.activityVisible = visible
        if (visible) bluetooth.refresh()
    }

    override fun onCleared() {
        activation.activityVisible = false
        super.onCleared()
    }

    fun tryAutomaticStart(config: AppConfig, snapshots: BluetoothSnapshot) {
        val address = config.headsetAddress ?: return
        if (snapshots.outputs.any { it.address == address }) activation.connected(config, address)
        else if (!bluetooth.connected(address)) activation.disconnected(address)
    }

    fun start(config: AppConfig) {
        if (config.sources.isEmpty()) return
        val address = config.headsetAddress?.takeIf {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (!config.handsFree || config.headsetOnlyReplies || bluetooth.connected(it))
        }
        val replies = config.handsFree && (!config.headsetOnlyReplies || bluetooth.hasPermission())
        DriveForegroundService.start(context, replies, config.autoSend, address)
    }

    fun stop(config: AppConfig) = activation.stop(config)
    fun replay() = DriveForegroundService.replay(context)
    fun setSource(app: MessageApp, enabled: Boolean) = change { settings.setSource(app, enabled) }
    fun selectHeadset(address: String?) = change { settings.selectHeadset(address) }
    fun setAutoStart(enabled: Boolean) = change { settings.setAutoStart(enabled) }
    fun setHandsFree(enabled: Boolean) = change { settings.setHandsFree(enabled) }
    fun setHeadsetOnlyReplies(enabled: Boolean) = change { settings.setHeadsetOnlyReplies(enabled) }
    fun setAutoSend(enabled: Boolean) = change { settings.setAutoSend(enabled) }
    fun setVipOnly(enabled: Boolean) = change { settings.setVipOnly(enabled) }
    fun removeVip(contact: VipContact) = change { settings.removeVip(contact) }

    fun addVip(app: MessageApp, name: String, onAdded: () -> Unit) = change {
        if (settings.addVip(app, name)) onAdded() else issue.value = SettingsIssue.INVALID_VIP
    }

    fun dismissIssue() { issue.value = null }

    private fun change(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (_: IOException) {
                issue.value = SettingsIssue.STORAGE
            }
        }
    }
}

internal enum class SettingsIssue { STORAGE, INVALID_VIP }
