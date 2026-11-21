package com.drivevoice.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.drivevoice.app.data.notification.NotificationInbox
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@AndroidEntryPoint
class MessageNotificationListener : NotificationListenerService() {
    @Inject internal lateinit var inbox: NotificationInbox

    override fun onListenerConnected() {
        super.onListenerConnected()
        inbox.bind { checkNotNull(activeNotifications) }
        connection.value = true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        inbox.posted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        inbox.removed(sbn.key)
    }

    override fun onListenerDisconnected() {
        inbox.unbind()
        connection.value = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        inbox.unbind()
        connection.value = false
        super.onDestroy()
    }

    companion object {
        private val connection = MutableStateFlow(false)
        internal val isConnected = connection.asStateFlow()
    }
}
