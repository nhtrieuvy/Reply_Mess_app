package com.drivevoice.app.data.notification

import android.app.KeyguardManager
import android.content.Context
import android.os.Process
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import com.drivevoice.app.data.model.MessageItem
import com.drivevoice.app.data.model.NotificationLifetime
import com.drivevoice.app.data.model.ReplyTarget
import com.drivevoice.app.data.model.ReplyMode
import com.drivevoice.app.domain.state.ReplyRecording
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
        val endpoints: Map<ReplyMode, InlineReply>,
    )

    private val snapshots = LinkedHashMap<String, Seen>(MAX_NOTIFICATIONS, 0.75f, true)
    private val replies = LinkedHashMap<String, Reply>(MAX_NOTIFICATIONS, 0.75f, true)
    private val lifetimes = LinkedHashMap<String, NotificationLifetime>(MAX_NOTIFICATIONS, 0.75f, true)
    private var replyVersion = 0L
    private var snapshotReader: (() -> Array<StatusBarNotification>)? = null
    private var queue: MessageQueueManager? = null
    private var repliesEnabled = false
    private var accepts: (MessageItem) -> Boolean = { true }
    private var onWithdrawal: (NotificationLifetime) -> Unit = {}

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
    fun start(
        newQueue: MessageQueueManager,
        enableReplies: Boolean = false,
        onWithdrawn: (NotificationLifetime) -> Unit = {},
        filter: (MessageItem) -> Boolean = { true },
    ): Boolean {
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
        accepts = filter
        onWithdrawal = onWithdrawn
        return true
    }

    @MainThread
    fun posted(notification: StatusBarNotification) {
        val destination = queue ?: return
        val snapshot = parse(notification) ?: run {
            replies.remove(notification.key)
            withdraw(notification.key)
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
        val accepted = incoming.filter { accepts(it.item) }
        val reply = if (repliesEnabled && (accepted.isNotEmpty() || notification.key in replies)) {
            updateReply(notification, snapshot)
        } else null
        if (accepted.isEmpty()) return
        val lifetime = lifetimes.getOrPut(notification.key) { NotificationLifetime() }
        if (lifetimes.size > MAX_NOTIFICATIONS) withdraw(lifetimes.keys.first())
        val receivedAt = SystemClock.elapsedRealtime()
        accepted.forEach {
            val target = reply?.target?.takeIf { _ -> it.item.sender.conversation == reply.conversation }
            destination.enqueue(it.item.copy(reply = target, notification = lifetime, receivedAtMs = receivedAt))
        }
    }

    @MainThread
    fun removed(key: String) {
        replies.remove(key)
        withdraw(key)
        // A styled notification can be reposted with the same conversation history.
        if (snapshots[key]?.styled == false) snapshots.remove(key)
    }

    @MainThread
    fun stop() {
        queue = null
        snapshots.clear()
        replies.clear()
        lifetimes.values.forEach(NotificationLifetime::withdraw)
        lifetimes.clear()
        repliesEnabled = false
        accepts = { true }
        onWithdrawal = {}
    }

    private fun withdraw(key: String) {
        val lifetime = lifetimes.remove(key) ?: return
        queue?.withdraw(lifetime)
        onWithdrawal(lifetime)
    }

    @MainThread
    fun canReply(context: Context, target: ReplyTarget): Boolean = currentReply(context, target, ReplyMode.TEXT) != null

    @MainThread
    fun canSendRecording(context: Context, target: ReplyTarget): Boolean {
        val endpoint = currentReply(context, target, ReplyMode.RECORDING) ?: return false
        return verifiedAudioTarget(context, endpoint)
    }

    @MainThread
    fun sendReply(context: Context, target: ReplyTarget, text: String): Boolean {
        val endpoint = currentReply(context, target, ReplyMode.TEXT) ?: return false
        val sent = endpoint.send(context, text)
        if (sent) replies.remove(target.notificationKey)
        return sent
    }

    @MainThread
    fun sendRecording(context: Context, target: ReplyTarget, recording: ReplyRecording): Boolean {
        val endpoint = currentReply(context, target, ReplyMode.RECORDING) ?: return false
        if (!verifiedAudioTarget(context, endpoint)) return false
        val sent = endpoint.sendAudio(context, recording)
        if (sent) replies.remove(target.notificationKey)
        return sent
    }

    private fun currentReply(context: Context, target: ReplyTarget, mode: ReplyMode): InlineReply? {
        if (!repliesEnabled) return null
        val saved = replies[target.notificationKey]?.takeIf { it.target == target } ?: return null
        val savedEndpoint = saved.endpoints[mode] ?: return null
        return try {
            val post = snapshotReader?.invoke()?.firstOrNull { it.key == target.notificationKey } ?: return null
            val snapshot = parse(post) ?: return null
            val newest = snapshot.newest?.item ?: return null
            if (snapshot.replyLabel != target.label || !accepts(newest) ||
                newest.sender.conversation != saved.conversation
            ) return null
            val endpoint = InlineReply.find(post.notification, post.packageName, post.user, mode) ?: return null
            val locked = endpoint.requiresUnlock && context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            endpoint.takeIf { !locked && it.matches(savedEndpoint) }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun verifiedAudioTarget(context: Context, endpoint: InlineReply): Boolean {
        val mime = endpoint.mime ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val owner = endpoint.token.creatorPackage ?: return false
        val version = try {
            context.packageManager.getPackageInfo(owner, 0).longVersionCode
        } catch (_: Exception) {
            return false
        }
        return VerifiedAudioTarget(owner, version, mime) in VERIFIED_AUDIO_TARGETS
    }

    private fun updateReply(post: StatusBarNotification, snapshot: NotificationSnapshot): Reply? {
        val label = snapshot.replyLabel
        val conversation = snapshot.newest?.item?.sender?.conversation
        val endpoints = if (label != null && conversation != null && post.user == Process.myUserHandle()) {
            try {
                ReplyMode.entries.mapNotNull { mode ->
                    InlineReply.find(post.notification, post.packageName, post.user, mode)?.let { mode to it }
                }.toMap()
            } catch (_: RuntimeException) {
                emptyMap()
            }
        } else emptyMap()
        if (label == null || conversation == null || endpoints.isEmpty()) {
            replies.remove(post.key)
            return null
        }
        val current = replies[post.key]
        if (current != null && current.target.label == label && current.conversation == conversation &&
            current.endpoints.keys == endpoints.keys &&
            endpoints.all { (mode, endpoint) -> current.endpoints[mode]?.let(endpoint::matches) == true }
        ) return current
        val reply = Reply(ReplyTarget(post.key, ++replyVersion, label), conversation, endpoints)
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

    private data class VerifiedAudioTarget(val packageName: String, val version: Long, val mime: String)

    private companion object {
        // Add only recipient versions that played the confirmed WAV in an actual conversation.
        val VERIFIED_AUDIO_TARGETS = emptySet<VerifiedAudioTarget>()

        const val MAX_NOTIFICATIONS = 128
        const val MAX_FINGERPRINTS = 64
    }
}
