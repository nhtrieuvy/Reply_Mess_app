package com.drivevoice.app.domain.queue

import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.MessageItem
import com.drivevoice.app.data.model.MessageSender
import com.drivevoice.app.data.model.NotificationLifetime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Owned by one reading session. Call enqueue and close on the scope's dispatcher. */
internal class MessageQueueManager(private val scope: CoroutineScope) {
    private class Pending(val messages: MutableList<MessageItem>, var timer: Job? = null)

    private val pending = linkedMapOf<MessageSender, Pending>()
    private val ready = Channel<MessageBatch>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val batches = ready.receiveAsFlow()

    fun enqueue(message: MessageItem) {
        val sender = message.sender
        if (sender !in pending && pending.size >= MAX_SENDERS) {
            publish(pending.keys.first())
        }
        val existing = pending[sender]
        if (existing != null && existing.messages.sumOf { it.text.length } + message.text.length > MAX_BATCH_LENGTH) {
            publish(sender)
        }
        val batch = pending.getOrPut(sender) { Pending(mutableListOf()) }
        batch.messages += message
        batch.timer?.cancel()
        if (batch.messages.size >= MAX_MESSAGES) {
            publish(sender)
        } else {
            batch.timer = scope.launch {
                delay(DEBOUNCE_MS)
                // Publish on this coroutine without cancelling the timer itself.
                pending.remove(sender)
                sendAvailable(batch)
            }
        }
    }

    private fun publish(sender: MessageSender) {
        val batch = pending.remove(sender) ?: return
        batch.timer?.cancel()
        sendAvailable(batch)
    }

    private fun sendAvailable(batch: Pending) {
        val messages = batch.messages.filter { it.notification?.withdrawn != true }
        if (messages.isNotEmpty()) ready.trySend(MessageBatch(messages))
    }

    fun withdraw(notification: NotificationLifetime) {
        notification.withdraw()
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            val batch = iterator.next()
            if (batch.messages.removeAll { it.notification === notification } && batch.messages.isEmpty()) {
                batch.timer?.cancel()
                iterator.remove()
            }
        }
    }

    fun close() {
        pending.values.forEach { it.timer?.cancel() }
        pending.clear()
        ready.cancel()
    }

    private companion object {
        const val DEBOUNCE_MS = 4_000L
        const val MAX_SENDERS = 32
        const val MAX_MESSAGES = 6
        const val MAX_BATCH_LENGTH = 700
    }
}
