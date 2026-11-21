package com.drivevoice.app.domain.queue

import com.drivevoice.app.data.model.MessageApp
import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.MessageItem
import com.drivevoice.app.data.model.MessageSender
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageQueueManagerTest {
    @Test
    fun eachSenderHasAnIndependentFourSecondWindow() = runTest {
        val queue = MessageQueueManager(backgroundScope)
        val output = mutableListOf<MessageBatch>()
        backgroundScope.launch { queue.batches.collect { output += it } }

        queue.enqueue(message("An", "Tin đầu"))
        advanceTimeBy(2_000)
        queue.enqueue(message("Bình", "Tin khác"))
        advanceTimeBy(1_000)
        queue.enqueue(message("An", "Tin cuối"))
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue(output.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Tin khác"), output.map { it.text })

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("Tin khác", "Tin đầu. Tin cuối"), output.map { it.text })
    }

    @Test
    fun matchingNamesInDifferentConversationsStaySeparate() = runTest {
        val queue = MessageQueueManager(backgroundScope)
        val output = mutableListOf<MessageBatch>()
        backgroundScope.launch { queue.batches.collect { output += it } }
        queue.enqueue(message("An", "Tin riêng", conversation = "direct"))
        queue.enqueue(message("An", "Tin nhóm", conversation = "group"))
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf("Tin riêng", "Tin nhóm"), output.map { it.text })
    }

    @Test
    fun closingDiscardsPendingMessages() = runTest {
        val queue = MessageQueueManager(backgroundScope)
        val output = mutableListOf<MessageBatch>()
        backgroundScope.launch { queue.batches.collect { output += it } }
        queue.enqueue(message("An", "Bỏ tin này"))
        runCurrent()
        queue.close()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(output.isEmpty())
    }

    private fun message(sender: String, text: String, conversation: String = sender): MessageItem =
        MessageItem(MessageSender(MessageApp.ZALO, 0, conversation, sender), sender, text)
}
