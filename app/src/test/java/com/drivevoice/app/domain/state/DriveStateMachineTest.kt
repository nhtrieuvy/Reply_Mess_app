package com.drivevoice.app.domain.state

import com.drivevoice.app.data.model.MessageApp
import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.MessageItem
import com.drivevoice.app.data.model.MessageSender
import com.drivevoice.app.data.model.ReplyTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveStateMachineTest {
    @Test
    fun speaksBeforeListeningAndSendsOnlyAfterConfirmation() = runTest {
        val io = Interaction(VoiceInput.Heard("Mình sẽ đến nhé"), VoiceInput.Heard("Gửi!"))
        assertEquals(DriveResult.SENT, run(io))
        assertEquals(
            listOf("SPEAKING_MSG", "message", "LISTENING_REPLY", "DICTATION:6000", "CONFIRMING_REPLY",
                "confirm", "CONFIRMATION:4000", "SENDING", "send", "ack", "IDLE"),
            io.events,
        )
        assertEquals(listOf(target to "Mình sẽ đến nhé"), io.sent)
    }

    @Test
    fun unknownAndNegativeCommandsNeverSendEvenWithAutoSend() = runTest {
        for (input in listOf("đừng gửi", "gửi đi không", "hủy")) {
            val io = Interaction(VoiceInput.Heard("Bản nháp"), VoiceInput.Heard(input))
            assertEquals(DriveResult.DONE, run(io, auto = true))
            assertTrue(io.sent.isEmpty())
        }
        val failure = Interaction(VoiceInput.Heard("Bản nháp"), VoiceInput.Unavailable)
        assertEquals(DriveResult.RECOGNITION_UNAVAILABLE, run(failure, auto = true))
        assertTrue(failure.sent.isEmpty())
    }

    @Test
    fun silenceSendsOnlyWhenOptedIn() = runTest {
        val manual = Interaction(VoiceInput.Heard("Có"), VoiceInput.Silence)
        assertEquals(DriveResult.DONE, run(manual))
        assertTrue(manual.sent.isEmpty())
        val automatic = Interaction(VoiceInput.Heard("Có"), VoiceInput.Silence)
        assertEquals(DriveResult.SENT, run(automatic, auto = true))
        assertEquals(listOf(target to "Có"), automatic.sent)
        val timeout = Interaction(VoiceInput.Heard("Có"), VoiceInput.Timeout)
        assertEquals(DriveResult.DONE, run(timeout, auto = true))
        assertTrue(timeout.sent.isEmpty())
    }

    @Test
    fun headsetKeyReplaysIncomingThenConfirmsTheReadback() = runTest {
        val io = Interaction(VoiceInput.Button, VoiceInput.Heard("Bản nháp"), VoiceInput.Button)
        assertEquals(DriveResult.SENT, run(io))
        assertEquals(2, io.events.count { it == "message" })
        assertEquals(listOf(target to "Bản nháp"), io.sent)
    }

    @Test
    fun playbackErrorAndRevokedTargetDoNotAcknowledge() = runTest {
        val playback = Interaction(VoiceInput.Heard("Có"), VoiceInput.Heard("Gửi")).apply {
            confirmationResult = SpeechResult.FOCUS_UNAVAILABLE
        }
        assertEquals(DriveResult.FOCUS_UNAVAILABLE, run(playback))
        assertTrue(playback.sent.isEmpty())
        assertTrue("CONFIRMATION:4000" !in playback.events)

        val revoked = Interaction(VoiceInput.Heard("Có"), VoiceInput.Heard("Gửi")).apply { sendSucceeds = false }
        assertEquals(DriveResult.REPLY_FAILED, run(revoked))
        assertTrue("ack" !in revoked.events)
    }

    @Test
    fun readOnlyModeNeverStartsListening() = runTest {
        val io = Interaction()
        assertEquals(DriveResult.DONE, run(io, enabled = false))
        assertEquals(listOf("SPEAKING_MSG", "message", "IDLE"), io.events)
    }

    private suspend fun run(io: Interaction, enabled: Boolean = true, auto: Boolean = false): DriveResult =
        DriveStateMachine(io) { io.events += it.name }.read(batch, enabled, auto)

    private class Interaction(vararg inputs: VoiceInput) : DriveInteraction {
        private val script = ArrayDeque(inputs.toList())
        val sent = mutableListOf<Pair<ReplyTarget, String>>()
        val events = mutableListOf<String>()
        var sendSucceeds = true
        var confirmationResult = SpeechResult.DONE

        override suspend fun speak(prompt: DrivePrompt): SpeechResult = when (prompt) {
            is DrivePrompt.Message -> SpeechResult.DONE.also { events += "message" }
            is DrivePrompt.Confirm -> confirmationResult.also { events += "confirm" }
        }

        override suspend fun listen(window: ReplyWindow): VoiceInput {
            events += "${window.name}:${window.timeoutMs}"
            return script.removeFirst()
        }

        override fun canReply(target: ReplyTarget) = true

        override fun send(target: ReplyTarget, text: String): Boolean {
            events += "send"
            if (sendSucceeds) sent += target to text
            return sendSucceeds
        }

        override suspend fun acknowledge() { events += "ack" }
    }

    private companion object {
        val target = ReplyTarget("key", 1, "An")
        val batch = MessageBatch(listOf(MessageItem(MessageSender(MessageApp.ZALO, 0, "chat", "An"), "An", "Chào", target)))
    }
}
