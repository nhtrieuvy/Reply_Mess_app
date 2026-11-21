package com.drivevoice.app.data.notification

import android.app.KeyguardManager
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import com.drivevoice.app.data.model.ReplyTarget
import com.drivevoice.app.domain.queue.MessageQueueManager
import javax.inject.Inject
import javax.inject.Singleton

/** Entry point for listener callbacks. All mutable state belongs to the main thread. */
@Singleton
internal class NotificationInbox @Inject constructor() {
    private data class Seen(val styled: Boolean, val fingerprints: Set<String>, val latestTimestamp: Long)
    private data class Reply(
        val target: ReplyTarget,
        val conversation: String,
        val endpoint: InlineReply,
    )

    private val snapshots = LinkedHashMap<String, Seen>(MAX_NOTIFICATIONS, 0.75f, true)
    private val replies = LinkedHashMap<String, Reply>(MAX_NOTIFICATIONS, 0.75f, true)
    private var replyVersion = 0L
    private var snapshotReader: (() -> Array<StatusBarNotification>)? = null
    private var queue: MessageQueueManager? = null
    private var repliesEnabled = false

    @MainThread
    fun bind(reader: () -> Array<StatusBarNotification>) {
        snapshotReader = reader
    }

    @MainThread
    fun unbind() {
        snapshotReader = null
        stop()
    }

    @MainThread
    fun start(newQueue: MessageQueueManager, enableReplies: Boolean = false): Boolean {
        val reader = snapshotReader ?: return false
        val active = try {
            reader()
        } catch (_: RuntimeException) {
            return false
        }
        stop()
        for (notification in active.takeLast(MAX_NOTIFICATIONS)) {
            val snapshot = parse(notification) ?: continue
            remember(notification.key, snapshot, previous = null)
        }
        queue = newQueue
        repliesEnabled = enableReplies
        return true
    }

    @MainThread
    fun posted(notification: StatusBarNotification) {
        val destination = queue ?: return
        val snapshot = parse(notification) ?: run {
            replies.remove(notification.key)
            return
        }
        val previous = snapshots[notification.key]?.takeIf { it.styled == snapshot.usesMessagingStyle }
        val incoming = if (previous == null) {
            listOfNotNull(snapshot.newest)
        } else {
            snapshot.messages.distinctBy { it.fingerprint }.filter {
                it.timestamp >= previous.latestTimestamp && it.fingerprint !in previous.fingerprints
            }
        }
        remember(notification.key, snapshot, previous)
        val reply = if (repliesEnabled) updateReply(notification, snapshot) else null
        incoming.forEach {
            val target = reply?.target?.takeIf { _ -> it.item.sender.conversation == reply.conversation }
            destination.enqueue(it.item.copy(reply = target))
        }
    }

    @MainThread
    fun removed(key: String) {
        replies.remove(key)
        // A styled notification can be reposted with the same conversation history.
        if (snapshots[key]?.styled == false) snapshots.remove(key)
    }

    @MainThread
    fun stop() {
        queue = null
        snapshots.clear()
        replies.clear()
        repliesEnabled = false
    }

    @MainThread
    fun canReply(context: Context, target: ReplyTarget): Boolean = currentReply(context, target) != null

    @MainThread
    fun sendReply(context: Context, target: ReplyTarget, text: String): Boolean {
        val endpoint = currentReply(context, target) ?: return false
        val sent = endpoint.send(context, text)
        if (sent) replies.remove(target.notificationKey)
        return sent
    }

    private fun currentReply(context: Context, target: ReplyTarget): InlineReply? {
        if (!repliesEnabled) return null
        val saved = replies[target.notificationKey]?.takeIf { it.target == target } ?: return null
        return try {
            val post = snapshotReader?.invoke()?.firstOrNull { it.key == target.notificationKey } ?: return null
            val snapshot = parse(post) ?: return null
            if (snapshot.replyLabel != target.label ||
                snapshot.newest?.item?.sender?.conversation != saved.conversation
            ) return null
            val endpoint = InlineReply.find(post.notification, post.packageName, post.user) ?: return null
            val locked = endpoint.requiresUnlock && context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            endpoint.takeIf { !locked && it.matches(saved.endpoint) }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun updateReply(post: StatusBarNotification, snapshot: NotificationSnapshot): Reply? {
        val label = snapshot.replyLabel
        val conversation = snapshot.newest?.item?.sender?.conversation
        val endpoint = if (label != null && conversation != null && post.user == Process.myUserHandle()) {
            try {
                InlineReply.find(post.notification, post.packageName, post.user)
            } catch (_: RuntimeException) {
                null
            }
        } else null
        if (label == null || conversation == null || endpoint == null) {
            replies.remove(post.key)
            return null
        }
        val current = replies[post.key]
        if (current != null && current.target.label == label &&
            current.conversation == conversation && endpoint.matches(current.endpoint)
        ) return current
        val reply = Reply(ReplyTarget(post.key, ++replyVersion, label), conversation, endpoint)
        replies[post.key] = reply
        if (replies.size > MAX_NOTIFICATIONS) replies.remove(replies.keys.first())
        return reply
    }

    private fun remember(key: String, snapshot: NotificationSnapshot, previous: Seen?) {
        val current = snapshot.messages.map { it.fingerprint }
        val fingerprints = if (snapshot.usesMessagingStyle) {
            (previous?.fingerprints.orEmpty().filterNot { it in current } + current)
                .takeLast(MAX_FINGERPRINTS).toSet()
        } else current.toSet()
        snapshots[key] = Seen(
            snapshot.usesMessagingStyle, fingerprints,
            maxOf(previous?.latestTimestamp ?: 0L, snapshot.latestTimestamp),
        )
        if (snapshots.size > MAX_NOTIFICATIONS) snapshots.remove(snapshots.keys.first())
    }

    private fun parse(notification: StatusBarNotification): NotificationSnapshot? = try {
        NotificationMessageParser.parse(notification)
    } catch (_: RuntimeException) {
        // Extras may contain malformed parcels from another process. Never log their contents.
        null
    }

    private companion object {
        const val MAX_NOTIFICATIONS = 128
        const val MAX_FINGERPRINTS = 64
    }
}
