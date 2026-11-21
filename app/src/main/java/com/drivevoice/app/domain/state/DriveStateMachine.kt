package com.drivevoice.app.domain.state

import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.ReplyTarget
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class DriveState { IDLE, READY, SPEAKING_MSG, LISTENING_REPLY, CONFIRMING_REPLY, SENDING }
internal enum class SpeechResult { DONE, FOCUS_UNAVAILABLE, OFFLINE_VOICE_UNAVAILABLE, FAILED }
internal enum class ReplyWindow(val timeoutMs: Long) { DICTATION(6_000), CONFIRMATION(4_000) }
internal enum class DriveResult {
    DONE, FOCUS_UNAVAILABLE, OFFLINE_VOICE_UNAVAILABLE, PLAYBACK_ERROR, RECOGNITION_UNAVAILABLE, REPLY_FAILED, SENT,
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
    data class Confirm(val label: String, val draft: String, val autoSend: Boolean) : DrivePrompt
}

internal interface DriveInteraction {
    suspend fun speak(prompt: DrivePrompt): SpeechResult
    suspend fun listen(window: ReplyWindow): VoiceInput
    fun canReply(target: ReplyTarget): Boolean
    fun send(target: ReplyTarget, text: String): Boolean
    suspend fun acknowledge()
}

internal class DriveStateMachine(
    private val interaction: DriveInteraction,
    private val onState: (DriveState) -> Unit,
) {
    suspend fun read(batch: MessageBatch, repliesEnabled: Boolean, autoSend: Boolean): DriveResult {
        try {
            val target = batch.reply?.takeIf { repliesEnabled }
            var replayed = false
            while (true) {
                onState(DriveState.SPEAKING_MSG)
                playbackFailure(interaction.speak(DrivePrompt.Message(batch)))?.let { return it }
                if (target == null || !interaction.canReply(target)) return DriveResult.DONE

                onState(DriveState.LISTENING_REPLY)
                when (val input = interaction.listen(ReplyWindow.DICTATION)) {
                    VoiceInput.Unavailable -> return DriveResult.RECOGNITION_UNAVAILABLE
                    VoiceInput.Silence, VoiceInput.Timeout -> return DriveResult.DONE
                    VoiceInput.Button -> if (replayed) return DriveResult.DONE
                    is VoiceInput.Heard -> when (command(input.text)) {
                        Command.REPLAY -> if (replayed) return DriveResult.DONE
                        Command.CANCEL, Command.SEND -> return DriveResult.DONE
                        null -> {
                            val draft = input.text.trim()
                            if (draft.isEmpty() || draft.length > MAX_DRAFT) return DriveResult.DONE
                            return confirm(target, draft, autoSend)
                        }
                    }
                }
                replayed = true
            }
        } finally {
            onState(DriveState.IDLE)
        }
    }

    private suspend fun confirm(target: ReplyTarget, draft: String, autoSend: Boolean): DriveResult {
        var replayed = false
        while (true) {
            if (!interaction.canReply(target)) return DriveResult.REPLY_FAILED
            onState(DriveState.CONFIRMING_REPLY)
            playbackFailure(interaction.speak(DrivePrompt.Confirm(target.label, draft, autoSend)))?.let { return it }
            when (val input = interaction.listen(ReplyWindow.CONFIRMATION)) {
                VoiceInput.Unavailable -> return DriveResult.RECOGNITION_UNAVAILABLE
                VoiceInput.Button -> break
                VoiceInput.Silence -> if (autoSend) break else return DriveResult.DONE
                VoiceInput.Timeout -> return DriveResult.DONE
                is VoiceInput.Heard -> when (command(input.text)) {
                    Command.SEND -> break
                    Command.REPLAY -> if (replayed) return DriveResult.DONE
                    Command.CANCEL, null -> return DriveResult.DONE
                }
            }
            replayed = true
        }

        currentCoroutineContext().ensureActive()
        onState(DriveState.SENDING)
        if (!interaction.send(target, draft)) return DriveResult.REPLY_FAILED
        interaction.acknowledge()
        return DriveResult.SENT
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
