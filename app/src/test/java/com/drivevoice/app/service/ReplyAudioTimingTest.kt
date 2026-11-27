package com.drivevoice.app.service

import com.drivevoice.app.domain.state.ReplyWindow
import com.drivevoice.app.domain.state.VoiceInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyAudioTimingTest {
    @Test
    fun readbackWaitsForTheMediaRouteToStopFlapping() = runTest {
        val ready = awaitReplyPlaybackRoute { currentTime in 400L..550L || currentTime >= 1_200 }
        assertTrue(ready)
        assertEquals(1_500L, currentTime)
    }

    @Test
    fun readbackDoesNotProceedWhenTheExpectedRouteNeverReturns() = runTest {
        assertFalse(awaitReplyPlaybackRoute { false })
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun confirmationStillAcceptsSendFourSecondsAfterMicrophoneReadiness() = runTest {
        val ready = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<VoiceInput>()
        launch {
            delay(1_900)
            ready.complete(Unit)
            delay(3_900)
            outcome.complete(VoiceInput.Heard("Gửi"))
        }

        assertEquals(VoiceInput.Heard("Gửi"), awaitVoiceInput(ReplyWindow.CONFIRMATION, ready, outcome))
        assertEquals(5_800L, currentTime)
    }

    @Test
    fun manualConfirmationTimesOutWithoutAnExplicitCommand() = runTest {
        val ready = CompletableDeferred(Unit)
        val outcome = CompletableDeferred<VoiceInput>()
        assertEquals(VoiceInput.Timeout, awaitVoiceInput(ReplyWindow.CONFIRMATION, ready, outcome))
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun recognizerThatNeverBecomesReadyCannotTriggerAutoSend() = runTest {
        assertEquals(
            VoiceInput.Unavailable,
            awaitVoiceInput(ReplyWindow.CONFIRMATION, CompletableDeferred(), CompletableDeferred()),
        )
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun recognizerFailureDuringStartupEndsImmediately() = runTest {
        val outcome = CompletableDeferred<VoiceInput>()
        launch {
            delay(200)
            outcome.complete(VoiceInput.Unavailable)
        }
        assertEquals(
            VoiceInput.Unavailable,
            awaitVoiceInput(ReplyWindow.CONFIRMATION, CompletableDeferred(), outcome),
        )
        assertEquals(200L, currentTime)
    }
}
