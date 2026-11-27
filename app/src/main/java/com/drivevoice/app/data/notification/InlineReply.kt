package com.drivevoice.app.data.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import com.drivevoice.app.data.model.ReplyMode
import com.drivevoice.app.domain.state.ReplyRecording

internal data class InlineReply(
    val token: PendingIntent,
    val input: RemoteInput,
    val requiresUnlock: Boolean,
    val mime: String? = null,
) {
    fun matches(other: InlineReply): Boolean =
        token == other.token && input.resultKey == other.input.resultKey &&
            requiresUnlock == other.requiresUnlock && mime == other.mime

    fun send(context: Context, text: String): Boolean = try {
        val filled = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(input), filled, Bundle().apply { putCharSequence(input.resultKey, text) },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(filled, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        token.send(context, 0, filled)
        true
    } catch (_: PendingIntent.CanceledException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun sendAudio(context: Context, recording: ReplyRecording): Boolean {
        val mediaType = mime ?: return false
        val owner = token.creatorPackage ?: return false
        val uri = VoiceReplyStore.issue(context, owner, mediaType, recording) ?: return false
        var handedOff = false
        try {
            val filled = Intent()
            RemoteInput.addDataResultToIntent(input, filled, mapOf(mediaType to uri))
            token.send(context, 0, filled)
            handedOff = true
            return true
        } catch (_: PendingIntent.CanceledException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: SecurityException) {
            return false
        } finally {
            if (!handedOff) VoiceReplyStore.revoke(context, uri)
        }
    }

    companion object {
        fun find(
            notification: Notification, owner: String, user: UserHandle, mode: ReplyMode = ReplyMode.TEXT,
        ): InlineReply? {
            val actions = notification.actions.orEmpty()
            val explicit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                actions.filter { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            } else emptyList()
            val eligible = explicit.ifEmpty {
                actions.filter {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                        it.semanticAction == Notification.Action.SEMANTIC_ACTION_NONE
                }
            }
            return eligible.mapNotNull { action ->
                val token = action.actionIntent ?: return@mapNotNull null
                if (token.creatorPackage != owner || token.creatorUserHandle != user ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (token.isImmutable || token.isActivity))
                ) return@mapNotNull null
                val needsUnlock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && action.isAuthenticationRequired
                if (mode == ReplyMode.TEXT) {
                    val input = action.remoteInputs.orEmpty().singleOrNull()?.takeIf { it.allowFreeFormInput }
                        ?: return@mapNotNull null
                    InlineReply(token, input, needsUnlock)
                } else {
                    val inputs = action.remoteInputs.orEmpty().asSequence() +
                        action.dataOnlyRemoteInputs.orEmpty().asSequence()
                    inputs.mapNotNull mediaInput@ { input ->
                        val accepted = input.allowedDataTypes.orEmpty()
                        val mime = when {
                            "audio/wav" in accepted || "audio/*" in accepted -> "audio/wav"
                            "audio/x-wav" in accepted -> "audio/x-wav"
                            else -> return@mediaInput null
                        }
                        InlineReply(token, input, needsUnlock, mime)
                    }.singleOrNull()
                }
            }.singleOrNull()
        }
    }
}
