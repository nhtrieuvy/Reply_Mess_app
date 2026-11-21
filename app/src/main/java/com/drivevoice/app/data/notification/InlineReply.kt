package com.drivevoice.app.data.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserHandle

internal data class InlineReply(
    val token: PendingIntent,
    val input: RemoteInput,
    val requiresUnlock: Boolean,
) {
    fun matches(other: InlineReply): Boolean =
        token == other.token && input.resultKey == other.input.resultKey &&
            requiresUnlock == other.requiresUnlock

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

    companion object {
        fun find(notification: Notification, owner: String, user: UserHandle): InlineReply? {
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
                val input = action.remoteInputs.orEmpty().singleOrNull()?.takeIf { it.allowFreeFormInput }
                    ?: return@mapNotNull null
                val needsUnlock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && action.isAuthenticationRequired
                InlineReply(token, input, needsUnlock)
            }.singleOrNull()
        }
    }
}
