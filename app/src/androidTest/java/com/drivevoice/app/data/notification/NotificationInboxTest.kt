package com.drivevoice.app.data.notification

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drivevoice.app.domain.queue.MessageQueueManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NotificationInboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sender = Person.Builder().setKey("an").setName("An").build()

    @Test
    fun repeatedUpdatesAndExpandedHistoryAreNotReadAgain() = runTest {
        val queue = MessageQueueManager(backgroundScope)
        val output = mutableListOf<String>()
        backgroundScope.launch { queue.batches.collect { output += it.text } }
        val inbox = NotificationInbox()
        inbox.bind { arrayOf(styled(message("Đã có trước phiên đọc", 10))) }
        assertTrue(inbox.start(queue))

        val update = styled(message("Tin mới", 20), message("Tin lịch sử vừa được mở rộng", 5))
        inbox.posted(update)
        inbox.posted(update)
        inbox.removed(update.key)
        inbox.posted(update)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf("Tin mới"), output)
    }

    @Test
    fun firstPostDoesNotReadHistoryOrOutgoingMessages() = runTest {
        val queue = MessageQueueManager(backgroundScope)
        val output = mutableListOf<String>()
        backgroundScope.launch { queue.batches.collect { output += it.text } }
        val inbox = NotificationInbox()
        inbox.bind { emptyArray() }
        assertTrue(inbox.start(queue))

        inbox.posted(styled(message("Tin cũ", 10), message("Tôi đã trả lời", 20, null)))
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(output.isEmpty())

        inbox.posted(styled(message("Tin đến tiếp theo", 30)))
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf("Tin đến tiếp theo"), output)
    }

    @Test
    fun rejectNonMessageNotificationsAndAcceptZaloFallback() {
        val plain = builder().setContentTitle("An").setContentText("Hẹn gặp ở nhà").build()
        val summary = builder().setContentTitle("An").setContentText("Hai tin nhắn")
            .setGroup("inbox").setGroupSummary(true).build()
        val call = builder().setContentTitle("An").setContentText("Cuộc gọi đến")
            .setCategory(Notification.CATEGORY_CALL).build()
        val hidden = builder().setContentTitle("An").setContentText("Bạn có tin nhắn mới").build()
        val ongoing = builder().setContentTitle("An").setContentText("Đang đồng bộ").setOngoing(true).build()
        assertNull(NotificationMessageParser.parse(post("com.example.other", plain)))
        assertNull(NotificationMessageParser.parse(post("org.telegram.messenger", summary)))
        assertNull(NotificationMessageParser.parse(post("com.facebook.orca", call)))
        assertNull(NotificationMessageParser.parse(post("com.zing.zalo", hidden)))
        assertNull(NotificationMessageParser.parse(post("org.telegram.messenger.web", ongoing)))
        val message = NotificationMessageParser.parse(post("com.zing.zalo", plain))?.newest?.item
        assertNotNull(message)
        assertEquals("Hẹn gặp ở nhà", message?.text)
    }

    private fun builder() = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun message(text: String, time: Long, person: Person? = sender) =
        NotificationCompat.MessagingStyle.Message(text, time, person)

    private fun styled(vararg messages: NotificationCompat.MessagingStyle.Message): StatusBarNotification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Tôi").build())
        messages.forEach { style.addMessage(it) }
        return post(
            "org.telegram.messenger",
            builder().setCategory(Notification.CATEGORY_MESSAGE).setStyle(style).build(),
        )
    }

    @Suppress("DEPRECATION")
    private fun post(packageName: String, notification: Notification) = StatusBarNotification(
        packageName, packageName, 1, null, Process.myUid(), Process.myPid(), 0,
        notification, Process.myUserHandle(), 100L,
    )
}
