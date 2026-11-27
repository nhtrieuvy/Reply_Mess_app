package com.drivevoice.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCheckTest {
    @Test
    fun captureIsBoundedToFiveSecondsAtEitherSampleRate() {
        assertEquals(240_000, audioCheckMaxFrames(48_000))
        assertEquals(80_000, audioCheckMaxFrames(16_000))
    }

    @Test
    fun micLevelRespondsToPcmAmplitudeAndIgnoresUnreadSamples() {
        assertEquals(0f, audioCheckLevel(shortArrayOf(0, Short.MAX_VALUE), 1), 0f)
        val quiet = audioCheckLevel(shortArrayOf(1_000, -1_000), 2)
        val loud = audioCheckLevel(shortArrayOf(16_000, -16_000), 2)
        assertTrue(quiet in 0f..1f && loud in 0f..1f && loud > quiet)
    }

    @Test
    fun playbackRequiresAVisibleRouteAndRejectsUnexpectedOutputs() {
        assertFalse(audioCheckRouteMatches(null, emptySet()))
        assertTrue(audioCheckRouteMatches(null, setOf(2, 3)))
        assertTrue(audioCheckRouteMatches(setOf(2), setOf(2)))
        assertFalse(audioCheckRouteMatches(setOf(2), setOf(3)))
        assertFalse(audioCheckRouteMatches(setOf(2), setOf(2, 3)))
    }

    @Test
    fun driveModeAndAudioCheckCannotBothHoldTheAudioLease() {
        val gate = DriveAudioGate()
        val drive = requireNotNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
        drive.close()

        val check = requireNotNull(gate.tryAcquire())
        drive.close()
        assertNull(gate.tryAcquire())
        check.close()
        assertNotNull(gate.tryAcquire()?.also { it.close() })
    }
}
