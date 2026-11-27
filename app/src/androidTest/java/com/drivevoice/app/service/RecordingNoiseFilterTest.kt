package com.drivevoice.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingNoiseFilterTest {
    @Test
    fun nativeFilterReducesWhiteNoiseAtBothSupportedRatesWithoutChangingTheOriginal() {
        for (rate in listOf(16_000, 48_000)) {
            val random = Random(rate)
            val raw = ShortArray(rate + 17) { random.nextInt(-4000, 4001).toShort() }
            val original = raw.copyOf()
            val filtered = ShortArray(raw.size + 5) { Short.MAX_VALUE }
            requireNotNull(RecordingNoiseFilter.open(rate, detection = true)).use { filter ->
                var frames = 0
                filter.processAvailable(raw, raw.size, filtered) { evidence ->
                    assertTrue(evidence.speechProbability in 0f..1.01f)
                    frames++
                }
                filter.finish(raw, raw.size, filtered)
                assertEquals(100, frames)
            }
            assertTrue(raw.contentEquals(original))
            assertTrue(audioCheckLevel(filtered, raw.size) < audioCheckLevel(raw, raw.size))
            assertFalse(filtered.take(raw.size).any { it == Short.MAX_VALUE })
            assertEquals(Short.MAX_VALUE, filtered[raw.size])
        }
    }
}
