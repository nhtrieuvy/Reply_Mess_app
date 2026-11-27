package com.drivevoice.app.domain.state

import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.ReplyTarget
import com.drivevoice.app.data.model.ReplyMode
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class DriveState { IDLE, READY, SPEAKING_MSG, LISTENING_REPLY, CONFIRMING_REPLY, SENDING }
internal enum class SpeechResult { DONE, FOCUS_UNAVAILABLE, OFFLINE_VOICE_UNAVAILABLE, FAILED }
internal enum class ReplyWindow(val timeoutMs: Long) { DICTATION(6_000), CONFIRMATION(4_000) }
internal enum class DriveResult {
    DONE, CANCELLED, SILENCE_UNCONFIRMED, FOCUS_UNAVAILABLE, OFFLINE_VOICE_UNAVAILABLE, PLAYBACK_ERROR,
    RECOGNITION_UNAVAILABLE, DICTATION_UNCLEAR, CONFIRMATION_UNCLEAR, TARGET_EXPIRED, AUDIO_UNSUPPORTED, REPLY_FAILED, SENT,
}

internal sealed interface VoiceInput {
    data class Heard(val text: String) : VoiceInput
    data object Button : VoiceInput
    data object Silence : VoiceInput
    data object Timeout : VoiceInput
    data object Unavailable : VoiceInput
}

internal sealed interface DrivePrompt {
    data class Message(val batch: MessageBatch) : DrivePrompt
    data class Confirm(val label: String, val draft: String, val autoSend: Boolean, val manualOnly: Boolean) : DrivePrompt
    data class ConfirmRecording(val label: String) : DrivePrompt
}

internal interface DriveInteraction {
    suspend fun speak(prompt: DrivePrompt): SpeechResult
    suspend fun listen(window: ReplyWindow, automatic: Boolean = false): VoiceInput
    fun canReply(target: ReplyTarget): Boolean
    fun canSendRecording(target: ReplyTarget): Boolean
    suspend fun record(): ReplyRecording?
    suspend fun play(recording: ReplyRecording): SpeechResult
    fun send(target: ReplyTarget, text: String): Boolean
    fun sendRecording(target: ReplyTarget, recording: ReplyRecording): Boolean
}

internal class DriveStateMachine(
    private val interaction: DriveInteraction,
    private val onState: (DriveState) -> Unit,
) {
    suspend fun read(
        batch: MessageBatch,
        repliesEnabled: Boolean,
        autoSend: Boolean,
        mode: ReplyMode = ReplyMode.TEXT,
        manualOnly: Boolean = false,
    ): DriveResult {
        try {
            var replayed = false
            while (true) {
                onState(DriveState.SPEAKING_MSG)
                playbackFailure(interaction.speak(DrivePrompt.Message(batch)))?.let { return it }
                if (!repliesEnabled) return DriveResult.DONE
                val target = batch.reply ?: return if (mode == ReplyMode.RECORDING) DriveResult.AUDIO_UNSUPPORTED
                    else DriveResult.TARGET_EXPIRED
                if (mode == ReplyMode.RECORDING) return record(target)
                if (!interaction.canReply(target)) return DriveResult.TARGET_EXPIRED

                onState(DriveState.LISTENING_REPLY)
                when (val input = interaction.listen(ReplyWindow.DICTATION)) {
                    VoiceInput.Unavailable -> return DriveResult.RECOGNITION_UNAVAILABLE
                    VoiceInput.Silence, VoiceInput.Timeout -> return DriveResult.DICTATION_UNCLEAR
                    VoiceInput.Button -> if (replayed) return DriveResult.DICTATION_UNCLEAR
                    is VoiceInput.Heard -> when (command(input.text)) {
                        Command.REPLAY -> if (replayed) return DriveResult.DICTATION_UNCLEAR
                        Command.CANCEL -> return DriveResult.CANCELLED
                        Command.SEND -> return DriveResult.DICTATION_UNCLEAR
                        null -> {
                            val draft = input.text.trim()
                            if (draft.isEmpty() || draft.length > MAX_DRAFT) return DriveResult.DICTATION_UNCLEAR
                            return confirm(DrivePrompt.Confirm(target.label, draft, autoSend, manualOnly), autoSend,
                                valid = { interaction.canReply(target) },
                                send = { interaction.send(target, draft) },
                            )
                        }
                    }
                }
                replayed = true
            }
        } finally {
            onState(DriveState.IDLE)
        }
    }

    private suspend fun record(target: ReplyTarget): DriveResult {
        if (!interaction.canSendRecording(target)) return DriveResult.AUDIO_UNSUPPORTED
        onState(DriveState.LISTENING_REPLY)
        val recording = interaction.record() ?: return DriveResult.DICTATION_UNCLEAR
        return recording.use {
            confirm(
                DrivePrompt.ConfirmRecording(target.label), autoSend = false, recording = recording,
                valid = { interaction.canSendRecording(target) },
                send = { interaction.sendRecording(target, recording) },
            )
        }
    }

    private suspend fun confirm(
        prompt: DrivePrompt,
        autoSend: Boolean,
        recording: ReplyRecording? = null,
        valid: () -> Boolean,
        send: () -> Boolean,
    ): DriveResult {
        var replayed = false
        while (true) {
            if (!valid()) return DriveResult.TARGET_EXPIRED
            onState(DriveState.CONFIRMING_REPLY)
            if (recording != null) playbackFailure(interaction.play(recording))?.let { return it }
            playbackFailure(interaction.speak(prompt))?.let { return it }
            when (val input = interaction.listen(ReplyWindow.CONFIRMATION, autoSend)) {
                VoiceInput.Unavailable -> return DriveResult.CONFIRMATION_UNCLEAR
                VoiceInput.Button -> break
                VoiceInput.Silence, VoiceInput.Timeout -> {
                    if (input == VoiceInput.Silence && autoSend) break
                    return if (autoSend) DriveResult.SILENCE_UNCONFIRMED else DriveResult.CONFIRMATION_UNCLEAR
                }
                is VoiceInput.Heard -> when (command(input.text)) {
                    Command.SEND -> break
                    Command.REPLAY -> if (replayed) return DriveResult.CONFIRMATION_UNCLEAR
                    Command.CANCEL -> return DriveResult.CANCELLED
                    null -> return DriveResult.CONFIRMATION_UNCLEAR
                }
            }
            replayed = true
        }

        currentCoroutineContext().ensureActive()
        if (!valid()) return DriveResult.TARGET_EXPIRED
        onState(DriveState.SENDING)
        return if (send()) DriveResult.SENT else DriveResult.REPLY_FAILED
    }

    private fun playbackFailure(result: SpeechResult): DriveResult? = when (result) {
        SpeechResult.DONE -> null
        SpeechResult.FOCUS_UNAVAILABLE -> DriveResult.FOCUS_UNAVAILABLE
        SpeechResult.OFFLINE_VOICE_UNAVAILABLE -> DriveResult.OFFLINE_VOICE_UNAVAILABLE
        SpeechResult.FAILED -> DriveResult.PLAYBACK_ERROR
    }

    private enum class Command { SEND, CANCEL, REPLAY }

    private fun command(text: String): Command? {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "").replace('đ', 'd')
            .trim().trimEnd('.', '!', '?', '…').trimEnd().replace(spaces, " ")
        return when (normalized) {
            "gui" -> Command.SEND
            "huy" -> Command.CANCEL
            "doc lai" -> Command.REPLAY
            else -> null
        }
    }

    private companion object {
        const val MAX_DRAFT = 350
        val marks = Regex("\\p{M}+")
        val spaces = Regex("\\s+")
    }
}
