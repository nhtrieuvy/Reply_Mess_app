package com.drivevoice.app.data.model

internal enum class MessageApp(val label: String, vararg val packages: String) {
    ZALO("Zalo", "com.zing.zalo"),
    MESSENGER("Messenger", "com.facebook.orca"),
    TELEGRAM("Telegram", "org.telegram.messenger", "org.telegram.messenger.web"),
}

internal data class MessageSender(
    val app: MessageApp,
    val profileId: Int,
    val conversation: String,
    val person: String,
)

internal data class MessageItem(
    val sender: MessageSender,
    val displayName: String,
    val text: String,
    val reply: ReplyTarget? = null,
    val notification: NotificationLifetime? = null,
    val receivedAtMs: Long? = null,
)

/** Main-thread token for one posted notification, independent of a reused Android key. */
internal class NotificationLifetime {
    var withdrawn = false
        private set

    fun withdraw() { withdrawn = true }
}

internal fun MessageItem.isAvailable(nowMs: Long, maxAgeMs: Long): Boolean =
    notification?.withdrawn != true && receivedAtMs?.let { nowMs - it < maxAgeMs } != false

internal data class ReplyTarget(val notificationKey: String, val version: Long, val label: String)

internal data class MessageBatch(val messages: List<MessageItem>) {
    val sender: MessageSender get() = messages.first().sender
    val displayName: String get() = messages.first().displayName
    val text: String get() = messages.joinToString(". ") { it.text }
    val reply: ReplyTarget? get() = messages.last().reply
}
