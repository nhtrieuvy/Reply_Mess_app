package com.drivevoice.app.data.notification

import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import com.drivevoice.app.domain.queue.MessageQueueManager
import javax.inject.Inject
import javax.inject.Singleton

/** Entry point for listener callbacks. All mutable state belongs to the main thread. */
@Singleton
internal class NotificationInbox @Inject constructor() {
    private data class Seen(val styled: Boolean, val fingerprints: Set<String>, val latestTimestamp: Long)

    private val snapshots = LinkedHashMap<String, Seen>(MAX_NOTIFICATIONS, 0.75f, true)
    private var snapshotReader: (() -> Array<StatusBarNotification>)? = null
    private var queue: MessageQueueManager? = null

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
    fun start(newQueue: MessageQueueManager): Boolean {
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
        return true
    }

    @MainThread
    fun posted(notification: StatusBarNotification) {
        val destination = queue ?: return
        val snapshot = parse(notification) ?: return
        val previous = snapshots[notification.key]?.takeIf { it.styled == snapshot.usesMessagingStyle }
        val incoming = if (previous == null) {
            listOfNotNull(snapshot.newest)
        } else {
            snapshot.messages.distinctBy { it.fingerprint }.filter {
                it.timestamp >= previous.latestTimestamp && it.fingerprint !in previous.fingerprints
            }
        }
        remember(notification.key, snapshot, previous)
        incoming.forEach { destination.enqueue(it.item) }
    }

    @MainThread
    fun removed(key: String) {
        // A styled notification can be reposted with the same conversation history.
        if (snapshots[key]?.styled == false) snapshots.remove(key)
    }

    @MainThread
    fun stop() {
        queue = null
        snapshots.clear()
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
