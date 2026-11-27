package com.drivevoice.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteredConfirmationTest {
    private val noVoice = FrameEvidence(speechProbability = .08f, masked = true)
    private val eof = PcmRecognitionResult(clean = true, endedAfterEof = true)

    @Test
    fun rawWindAndAdvisoryStartDoNotPreventFourSecondsOfFilteredQuiet() {
        val tracker = FilteredConfirmation()
        repeat(399) { tracker.sample(noVoice) }
        assertFalse(tracker.canCloseForSilence(4_000))
        // There is deliberately no input from Android's onBeginningOfSpeech callback.
        tracker.sample(noVoice)
        assertFalse(tracker.canCloseForSilence(3_999))
        assertTrue(tracker.maySend(tracker.canCloseForSilence(4_000), eof, textObserved = false))
    }

    @Test
    fun briefUncertaintyRestartsTheContinuousWindow() {
        val tracker = FilteredConfirmation()
        repeat(350) { tracker.sample(noVoice) }
        tracker.sample(FrameEvidence(.40f, masked = false))
        repeat(399) { tracker.sample(noVoice) }
        assertFalse(tracker.canCloseForSilence(7_500))
        tracker.sample(noVoice)
        assertTrue(tracker.canCloseForSilence(7_510))
    }

    @Test
    fun speechPartialResultsAndDamageIndependentlyBlockAutomaticConfirmation() {
        val speech = FilteredConfirmation()
        repeat(399) { speech.sample(noVoice) }
        speech.sample(FrameEvidence(.75f, masked = true))
        repeat(400) { speech.sample(noVoice) }
        assertFalse(speech.canCloseForSilence(8_000))

        val partial = FilteredConfirmation()
        partial.observedText()
        repeat(400) { partial.sample(noVoice) }
        assertFalse(partial.canCloseForSilence(4_000))

        val clipping = FilteredConfirmation()
        clipping.sample(FrameEvidence(.03f, masked = true, clipped = true))
        repeat(400) { clipping.sample(noVoice) }
        assertFalse(clipping.canCloseForSilence(8_000))
    }

    @Test
    fun needsCleanTerminalCallbackAfterEofAndChecksForCancelDuringDrain() {
        val tracker = FilteredConfirmation()
        repeat(400) { tracker.sample(noVoice) }
        val quiet = tracker.canCloseForSilence(4_000)
        assertFalse(tracker.maySend(quiet, null, textObserved = false))
        assertFalse(tracker.maySend(quiet, eof.copy(clean = false), textObserved = false))
        assertFalse(tracker.maySend(quiet, eof.copy(endedAfterEof = false), textObserved = false))
        assertFalse(tracker.maySend(quiet, eof, textObserved = true))
        assertFalse(tracker.maySend(quiet, eof.copy(text = "Hủy"), textObserved = false))
    }
}
