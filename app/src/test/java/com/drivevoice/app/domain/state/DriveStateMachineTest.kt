package com.drivevoice.app.domain.state

import com.drivevoice.app.data.model.MessageApp
import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.MessageItem
import com.drivevoice.app.data.model.MessageSender
import com.drivevoice.app.data.model.ReplyTarget
import com.drivevoice.app.data.model.ReplyMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
                "confirm", "CONFIRMATION:4000", "SENDING", "send", "IDLE"),
            io.events,
        )
        assertEquals(listOf(target to "Mình sẽ đến nhé"), io.sent)
    }

    @Test
    fun unknownAndNegativeCommandsNeverSendEvenWithAutoSend() = runTest {
        for (input in listOf("đừng gửi", "gửi đi không")) {
            val io = Interaction(VoiceInput.Heard("Bản nháp"), VoiceInput.Heard(input))
            assertEquals(DriveResult.CONFIRMATION_UNCLEAR, run(io, auto = true))
            assertTrue(io.sent.isEmpty())
        }
        val failure = Interaction(VoiceInput.Heard("Bản nháp"), VoiceInput.Unavailable)
        assertEquals(DriveResult.CONFIRMATION_UNCLEAR, run(failure, auto = true))
        assertTrue(failure.sent.isEmpty())
    }

    @Test
    fun explicitCancelIsReportedAndNeverAutoSends() = runTest {
        val dictation = Interaction(VoiceInput.Heard("Hủy"))
        assertEquals(DriveResult.CANCELLED, run(dictation, auto = true))
        assertTrue(dictation.sent.isEmpty())
        val confirmation = Interaction(VoiceInput.Heard("Bản nháp"), VoiceInput.Heard("Hủy!"))
        assertEquals(DriveResult.CANCELLED, run(confirmation, auto = true))
        assertTrue(confirmation.sent.isEmpty())
    }

    @Test
    fun silenceSendsOnlyWhenOptedIn() = runTest {
        val manual = Interaction(VoiceInput.Heard("Có"), VoiceInput.Silence)
        assertEquals(DriveResult.CONFIRMATION_UNCLEAR, run(manual))
        assertTrue(manual.sent.isEmpty())
        val automatic = Interaction(VoiceInput.Heard("Có"), VoiceInput.Silence)
        assertEquals(DriveResult.SENT, run(automatic, auto = true))
        assertEquals(listOf(target to "Có"), automatic.sent)
        val timeout = Interaction(VoiceInput.Heard("Có"), VoiceInput.Timeout)
        assertEquals(DriveResult.SILENCE_UNCONFIRMED, run(timeout, auto = true))
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
    fun playbackErrorAndFailedHandoffDoNotReportSuccess() = runTest {
        val playback = Interaction(VoiceInput.Heard("Có"), VoiceInput.Heard("Gửi")).apply {
            confirmationResult = SpeechResult.FOCUS_UNAVAILABLE
        }
        assertEquals(DriveResult.FOCUS_UNAVAILABLE, run(playback))
        assertTrue(playback.sent.isEmpty())
        assertTrue("CONFIRMATION:4000" !in playback.events)

        val revoked = Interaction(VoiceInput.Heard("Có"), VoiceInput.Heard("Gửi")).apply { sendSucceeds = false }
        assertEquals(DriveResult.REPLY_FAILED, run(revoked))
        assertTrue(revoked.sent.isEmpty())
    }

    @Test
    fun waitsForReadbackBeforeOpeningConfirmationOrAutoSending() = runTest {
        val readbackStarted = CompletableDeferred<Unit>()
        val finishReadback = CompletableDeferred<Unit>()
        val io = Interaction(VoiceInput.Heard("Bản nháp"), VoiceInput.Silence).apply {
            onReadback = {
                readbackStarted.complete(Unit)
                finishReadback.await()
            }
        }
        val turn = async { run(io, auto = true) }
        readbackStarted.await()
        assertTrue("CONFIRMATION:4000" !in io.events)
        assertTrue(io.sent.isEmpty())
        finishReadback.complete(Unit)
        assertEquals(DriveResult.SENT, turn.await())
    }

    @Test
    fun interruptedReadbackCannotAutoSend() = runTest {
        val io = Interaction(VoiceInput.Heard("Bản nháp"), VoiceInput.Silence).apply {
            confirmationResult = SpeechResult.FAILED
        }
        assertEquals(DriveResult.PLAYBACK_ERROR, run(io, auto = true))
        assertTrue("CONFIRMATION:4000" !in io.events)
        assertTrue(io.sent.isEmpty())
    }

    @Test
    fun readOnlyModeNeverStartsListening() = runTest {
        val io = Interaction()
        assertEquals(DriveResult.DONE, run(io, enabled = false))
        assertEquals(listOf("SPEAKING_MSG", "message", "IDLE"), io.events)
    }

    @Test
    fun recordingRequiresExplicitConfirmationEvenWhenTextAutoSendIsEnabled() = runTest {
        val quiet = Interaction(VoiceInput.Silence).apply { supportsRecording = true }
        assertEquals(DriveResult.CONFIRMATION_UNCLEAR, run(quiet, auto = true, mode = ReplyMode.RECORDING))
        assertTrue(quiet.sent.isEmpty())
        assertTrue("send-audio" !in quiet.events)

        val explicit = Interaction(VoiceInput.Button).apply { supportsRecording = true }
        assertEquals(DriveResult.SENT, run(explicit, auto = true, mode = ReplyMode.RECORDING))
        assertEquals(
            listOf("SPEAKING_MSG", "message", "LISTENING_REPLY", "record", "CONFIRMING_REPLY",
                "play-audio", "confirm-audio", "CONFIRMATION:4000", "SENDING", "send-audio", "IDLE"),
            explicit.events,
        )
        assertTrue(explicit.sent.isEmpty())
    }

    @Test
    fun unsupportedAudioDoesNotOpenTheMicOrFallBackToText() = runTest {
        val io = Interaction()
        assertEquals(DriveResult.AUDIO_UNSUPPORTED, run(io, mode = ReplyMode.RECORDING))
        assertEquals(listOf("SPEAKING_MSG", "message", "IDLE"), io.events)
    }

    @Test
    fun failedTextRecognitionDoesNotSwitchToRecording() = runTest {
        val io = Interaction(VoiceInput.Unavailable).apply { supportsRecording = true }
        assertEquals(DriveResult.RECOGNITION_UNAVAILABLE, run(io))
        assertTrue("record" !in io.events)
    }

    @Test
    fun expiredRecordingTargetIsRecheckedAfterPlaybackAndConfirmation() = runTest {
        val io = Interaction(VoiceInput.Heard("Gửi")).apply {
            supportsRecording = true
            onReadback = { supportsRecording = false }
        }
        assertEquals(DriveResult.TARGET_EXPIRED, run(io, mode = ReplyMode.RECORDING))
        assertTrue("send-audio" !in io.events)
    }

    private suspend fun run(
        io: Interaction, enabled: Boolean = true, auto: Boolean = false, mode: ReplyMode = ReplyMode.TEXT,
    ): DriveResult = DriveStateMachine(io) { io.events += it.name }.read(batch, enabled, auto, mode)

    private class Interaction(vararg inputs: VoiceInput) : DriveInteraction {
        private val script = ArrayDeque(inputs.toList())
        val sent = mutableListOf<Pair<ReplyTarget, String>>()
        val events = mutableListOf<String>()
        var sendSucceeds = true
        var supportsRecording = false
        var confirmationResult = SpeechResult.DONE
        var onReadback: suspend () -> Unit = {}

        override suspend fun speak(prompt: DrivePrompt): SpeechResult = when (prompt) {
            is DrivePrompt.Message -> SpeechResult.DONE.also { events += "message" }
            is DrivePrompt.Confirm -> {
                events += "confirm"
                onReadback()
                confirmationResult
            }
            is DrivePrompt.ConfirmRecording -> {
                events += "confirm-audio"
                onReadback()
                confirmationResult
            }
        }

        override suspend fun listen(window: ReplyWindow, automatic: Boolean): VoiceInput {
            events += "${window.name}:${window.timeoutMs}"
            return script.removeFirst()
        }

        override fun canReply(target: ReplyTarget) = true

        override fun send(target: ReplyTarget, text: String): Boolean {
            events += "send"
            if (sendSucceeds) sent += target to text
            return sendSucceeds
        }

        override fun canSendRecording(target: ReplyTarget): Boolean = supportsRecording
        override suspend fun record() = ReplyRecording(shortArrayOf(10, 20), 16_000).also { events += "record" }
        override suspend fun play(recording: ReplyRecording) = SpeechResult.DONE.also { events += "play-audio" }
        override fun sendRecording(target: ReplyTarget, recording: ReplyRecording) = true.also { events += "send-audio" }
    }

    private companion object {
        val target = ReplyTarget("key", 1, "An")
        val batch = MessageBatch(listOf(MessageItem(MessageSender(MessageApp.ZALO, 0, "chat", "An"), "An", "Chào", target)))
    }
}
