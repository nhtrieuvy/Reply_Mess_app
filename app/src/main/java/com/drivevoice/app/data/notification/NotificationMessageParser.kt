package com.drivevoice.app.data.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.drivevoice.app.data.model.MessageApp
import com.drivevoice.app.data.model.MessageItem
import com.drivevoice.app.data.model.MessageSender
import java.security.MessageDigest
import java.util.Locale

internal data class NotificationMessage(
    val fingerprint: String,
    val item: MessageItem,
    val timestamp: Long,
)

internal data class NotificationSnapshot(
    val messages: List<NotificationMessage>,
    val newest: NotificationMessage?,
    val usesMessagingStyle: Boolean,
    val latestTimestamp: Long = 0L,
    val replyLabel: String? = null,
)

internal object NotificationMessageParser {
    fun parse(post: StatusBarNotification): NotificationSnapshot? {
        val app = MessageApp.entries.firstOrNull { post.packageName in it.packages } ?: return null
        val notification = post.notification
        if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0 ||
            notification.visibility == Notification.VISIBILITY_SECRET ||
            (notification.category != null && notification.category != Notification.CATEGORY_MESSAGE)
        ) return null

        val digest = MessageDigest.getInstance("SHA-256")
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) {
            // MessagingStyle identifies outgoing messages by the absence of a sender.
            val live = style.messages.takeLast(32)
            val messages = live.map { message ->
                val person = message.person ?: return@map null
                val name = clean(person.name, NAME_LIMIT) ?: return@map null
                val text = clean(message.text, TEXT_LIMIT) ?: return@map null
                if (isGeneric(text, app)) return@map null
                val sender = person.key ?: person.uri ?: name
                val conversation = notification.shortcutId
                    ?: clean(style.conversationTitle, NAME_LIMIT)
                    ?: if (style.isGroupConversation) post.key else name
                makeMessage(digest, post, app, conversation, sender, name, text, message.timestamp)
            }
            return NotificationSnapshot(
                messages.filterNotNull(), messages.lastOrNull(), usesMessagingStyle = true,
                latestTimestamp = live.maxOfOrNull { it.timestamp } ?: 0L,
                replyLabel = clean(style.conversationTitle, NAME_LIMIT)
                    ?: messages.lastOrNull()?.item?.displayName?.takeUnless { style.isGroupConversation },
            )
        }

        val extras = notification.extras ?: return null
        if (!extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).isNullOrEmpty()) return null
        val name = clean(extras.getCharSequence(Notification.EXTRA_TITLE), NAME_LIMIT) ?: return null
        val text = clean(extras.getCharSequence(Notification.EXTRA_TEXT), TEXT_LIMIT)
            ?: clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT), TEXT_LIMIT) ?: return null
        if (isGeneric(name, app) || isGeneric(text, app)) return null
        val conversation = notification.shortcutId
            ?: clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE), NAME_LIMIT)
            ?: name
        val message = makeMessage(digest, post, app, conversation, name, name, text, timestamp = 0L)
        return NotificationSnapshot(
            listOf(message), message, usesMessagingStyle = false,
            replyLabel = clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE), NAME_LIMIT)
                ?: name.takeIf { !notification.shortcutId.isNullOrBlank() },
        )
    }

    private fun makeMessage(
        digest: MessageDigest,
        post: StatusBarNotification,
        app: MessageApp,
        conversation: String,
        person: String,
        name: String,
        text: String,
        timestamp: Long,
    ): NotificationMessage {
        val sender = MessageSender(app, post.user.hashCode(), conversation, person)
        val payload = "$conversation\u0000$person\u0000$timestamp\u0000$text"
        val fingerprint = String(digest.digest(payload.toByteArray()), Charsets.ISO_8859_1)
        return NotificationMessage(fingerprint, MessageItem(sender, name, text), timestamp)
    }

    private fun clean(text: CharSequence?, limit: Int): String? {
        val normalized = text?.take(limit * 4)?.toString()?.replace(whitespace, " ")?.trim()
            ?.take(limit)?.trimEnd()?.takeIf { it.isNotEmpty() } ?: return null
        return if (normalized.last().isHighSurrogate()) normalized.dropLast(1).takeIf { it.isNotEmpty() }
        else normalized
    }

    private fun isGeneric(text: String, app: MessageApp): Boolean {
        val key = text.lowercase(Locale.ROOT).trimEnd('.', '!', ':', '…')
        return key == app.label.lowercase(Locale.ROOT) || key in genericLabels ||
            genericCount.matches(key) || genericCall.matches(key)
    }

    private const val NAME_LIMIT = 96
    private const val TEXT_LIMIT = 350
    private val whitespace = Regex("[\\p{Z}\\s]+")
    private val genericLabels = setOf(
        "tin nhắn mới", "bạn có tin nhắn mới", "thông báo mới", "nội dung bị ẩn", "nội dung đã ẩn",
        "đang đồng bộ", "đang kết nối", "đang kiểm tra tin nhắn mới", "đang chạy trong nền",
        "new message", "new messages", "you have a new message", "you have new messages",
        "content hidden", "notification content hidden", "new notification",
        "checking for new messages", "you may have new messages", "syncing", "connecting",
        "running in the background",
    )
    private val genericCount = Regex(
        "(?:bạn có )?\\d+\\s+(?:tin nhắn(?: mới| chưa đọc)?|new messages?|unread messages?|messages?)" +
            "(?:\\s+(?:from|từ)\\s+.+)?",
    )
    private val genericCall = Regex(
        "(?:cuộc gọi(?: thoại| video)? (?:đến|nhỡ|bị nhỡ)|missed (?:voice |video )?call|" +
            "incoming (?:voice |video )?call)(?:\\s+(?:from|từ)\\s+.+)?",
    )
}
