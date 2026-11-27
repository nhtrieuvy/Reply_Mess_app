package com.drivevoice.app.data.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.core.content.ContextCompat
import com.drivevoice.app.data.model.ReplyMode
import com.drivevoice.app.domain.state.ReplyRecording
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = 31)
@RunWith(AndroidJUnit4::class)
class InlineReplyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = context.packageName
    private val user = Process.myUserHandle()

    @Test
    fun preservesVietnameseTextAndUsesOnlyTheSelectedRemoteInput() {
        val result = ArrayBlockingQueue<String>(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.offer(RemoteInput.getResultsFromIntent(intent)?.getCharSequence("answer").toString())
            }
        }
        val action = "$owner.TEST_REPLY"
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val token = token(action, PendingIntent.FLAG_MUTABLE)
        try {
            val endpoint = InlineReply.find(notification(replyAction(token)), owner, user)
            assertNotNull(endpoint)
            assertTrue(checkNotNull(endpoint).send(context, "Mình sẽ đến nhé"))
            assertEquals("Mình sẽ đến nhé", result.poll(5, TimeUnit.SECONDS))
            token.cancel()
            assertFalse(endpoint.send(context, "Không gửi được"))
        } finally {
            token.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun rejectsImmutableForeignAndAmbiguousActions() {
        val mutable = token("$owner.TEST_MUTABLE", PendingIntent.FLAG_MUTABLE)
        val immutable = token("$owner.TEST_IMMUTABLE", PendingIntent.FLAG_IMMUTABLE)
        try {
            val normal = replyAction(mutable)
            assertNull(InlineReply.find(notification(normal), "another.app", user))
            assertNull(InlineReply.find(notification(replyAction(immutable)), owner, user))
            assertNull(InlineReply.find(notification(normal, replyAction(mutable, "different")), owner, user))
        } finally {
            mutable.cancel()
            immutable.cancel()
        }
    }

    @Test
    fun audioRemoteInputReceivesTheExactWaveThroughTheTemporaryUri() {
        val received = ArrayBlockingQueue<ByteArray>(1)
        val action = "$owner.TEST_AUDIO_REPLY"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val uri = RemoteInput.getDataResultsFromIntent(intent, "audio")?.get("audio/wav") ?: return
                context.contentResolver.openInputStream(uri)?.use { received.offer(it.readBytes()) }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val token = token(action, PendingIntent.FLAG_MUTABLE)
        try {
            val audioAction = Notification.Action.Builder(null, "Voice reply", token)
                .addRemoteInput(RemoteInput.Builder("audio").setAllowFreeFormInput(false)
                    .setAllowDataType("audio/wav", true).build())
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .build()
            assertNull(InlineReply.find(notification(audioAction), owner, user))
            val endpoint = checkNotNull(InlineReply.find(notification(audioAction), owner, user, ReplyMode.RECORDING))
            ReplyRecording(shortArrayOf(-123, 456, 789), 16_000).use { recording ->
                assertTrue(endpoint.sendAudio(context, recording))
                assertArrayEquals(recording.wave(), received.poll(5, TimeUnit.SECONDS))
            }
            assertNull(InlineReply.find(notification(replyAction(token)), owner, user, ReplyMode.RECORDING))
        } finally {
            token.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    private fun token(action: String, flags: Int): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(action).setPackage(owner), flags,
    )

    private fun replyAction(token: PendingIntent, key: String = "answer"): Notification.Action =
        Notification.Action.Builder(null, "Trả lời", token)
            .addRemoteInput(RemoteInput.Builder(key).build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()

    private fun notification(vararg actions: Notification.Action): Notification =
        Notification.Builder(context, "test").setActions(*actions).build()
}
