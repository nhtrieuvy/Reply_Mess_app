package com.drivevoice.app.service

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MessageNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connection.value = true
    }

    override fun onListenerDisconnected() {
        connection.value = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connection.value = false
        super.onDestroy()
    }

    companion object {
        private val connection = MutableStateFlow(false)
        internal val isConnected = connection.asStateFlow()
    }
}
