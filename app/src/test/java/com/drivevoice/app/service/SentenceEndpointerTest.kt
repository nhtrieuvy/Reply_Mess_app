package com.drivevoice.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceEndpointerTest {
    private val speech = FrameEvidence(.9f, masked = false)
    private val quiet = FrameEvidence(.1f, masked = false)
    private val horn = FrameEvidence(.1f, masked = true)

    @Test
    fun keepsPreRollAndContinuesAcrossAShortPause() {
        val detector = SentenceEndpointer()
        repeat(60) { detector.add(quiet) }
        repeat(30) { detector.add(speech) }
        repeat(90) { assertEquals(SentenceState.LISTENING, detector.add(quiet)) }
        repeat(30) { detector.add(speech) }
        repeat(119) { assertEquals(SentenceState.LISTENING, detector.add(quiet)) }
        assertEquals(SentenceState.COMPLETE, detector.add(quiet))
        assertEquals(SentenceBounds(350, 2_350), detector.bounds)
    }

    @Test
    fun hornDoesNotCountAsSilenceOrEndTheSentence() {
        val detector = SentenceEndpointer()
        repeat(20) { detector.add(speech) }
        repeat(70) { detector.add(quiet) }
        repeat(100) { assertEquals(SentenceState.LISTENING, detector.add(horn)) }
        repeat(49) { assertEquals(SentenceState.LISTENING, detector.add(quiet)) }
        assertEquals(SentenceState.COMPLETE, detector.add(quiet))
        assertEquals(SentenceBounds(0, 2_150), detector.bounds)
    }

    @Test
    fun noiseAloneEndsAtThreeSecondsWithoutAClip() {
        val detector = SentenceEndpointer()
        repeat(299) { assertEquals(SentenceState.LISTENING, detector.add(horn)) }
        assertEquals(SentenceState.UNCLEAR, detector.add(horn))
        assertNull(detector.bounds)
    }

    @Test
    fun continuousSpeechOrMaskedEndingDoesNotSendACutOffSentence() {
        for (ending in listOf(speech, horn)) {
            val detector = SentenceEndpointer()
            repeat(20) { detector.add(speech) }
            repeat(979) { assertEquals(SentenceState.LISTENING, detector.add(ending)) }
            assertEquals(SentenceState.UNCLEAR, detector.add(ending))
            assertNull(detector.bounds)
        }
    }

    @Test
    fun manualStopRequiresSpeechAndDoesNotIncludeAudioAfterTheStop() {
        val noVoice = SentenceEndpointer()
        repeat(20) { noVoice.add(quiet) }
        assertEquals(SentenceState.UNCLEAR, noVoice.finishManually())

        val voice = SentenceEndpointer()
        repeat(30) { voice.add(speech) }
        assertEquals(SentenceState.COMPLETE, voice.finishManually())
        assertEquals(SentenceBounds(0, 300), voice.bounds)
        assertEquals(SentenceState.COMPLETE, voice.add(horn))
    }
}
