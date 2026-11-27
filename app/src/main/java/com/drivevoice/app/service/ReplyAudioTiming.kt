package com.drivevoice.app.service

import com.drivevoice.app.domain.state.ReplyWindow
import com.drivevoice.app.domain.state.VoiceInput
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun awaitReplyPlaybackRoute(isReady: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(4_000) {
        // Android can briefly report the media route before the communication route has fully settled.
        var consecutiveReady = 0
        while (consecutiveReady < 4) {
            consecutiveReady = if (isReady()) consecutiveReady + 1 else 0
            if (consecutiveReady < 4) delay(100)
        }
        true
    } == true

internal suspend fun awaitVoiceInput(
    window: ReplyWindow,
    ready: Deferred<Unit>,
    outcome: Deferred<VoiceInput>,
): VoiceInput {
    val started = withTimeoutOrNull(3_000) {
        select<Unit> {
            ready.onAwait { }
            outcome.onAwait { }
        }
        ready.isCompleted
    }
    if (started != true) return VoiceInput.Unavailable

    return withTimeoutOrNull(window.timeoutMs) { outcome.await() } ?: VoiceInput.Timeout
}
