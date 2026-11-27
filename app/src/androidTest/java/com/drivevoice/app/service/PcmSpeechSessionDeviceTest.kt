package com.drivevoice.app.service

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device with the offline Vietnamese voice and recognition model installed. */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class PcmSpeechSessionDeviceTest {
    @Test
    fun filteredWindCanConfirmAndLateSpeechPreventsAutomaticSend() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("drivevoicePcmDeviceTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) {
            val voice = VoiceEngineManager(context)
            val engine = ReplyPcmEngine(context, ReplyPcmCapture(context), voice)
            assertTrue("The recognizer must read a Vietnamese challenge from the pipe", engine.verify())

            val random = Random(36)
            val wind = ShortArray(REPLY_SAMPLE_RATE * 8) { index ->
                val low = 2200 * sin(index * 2 * Math.PI * 65 / REPLY_SAMPLE_RATE)
                (low + random.nextInt(-450, 451)).toInt().toShort()
            }
            for (filteredRecognition in listOf(false, true)) {
                delay(500)
                assertTrue("Four seconds of filtered wind must qualify as no speech (filter=$filteredRecognition)",
                    testWindow(wind, filteredRecognition),
                )
            }

            try {
                assertTrue(voice.initialize())
                val phrase = checkNotNull(voice.synthesizeProbe("Hủy đi"))
                val lateStart = REPLY_SAMPLE_RATE * 36 / 10
                val lateSpeech = ShortArray(lateStart + phrase.size)
                wind.copyInto(lateSpeech, endIndex = lateStart)
                phrase.copyInto(lateSpeech, destinationOffset = lateStart)
                assertTrue("Late speech must prevent auto-send", !testWindow(lateSpeech, true))
            } finally {
                voice.close()
            }
        }
    }

    private suspend fun testWindow(samples: ShortArray, filteredRecognition: Boolean): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clean = ShortArray(samples.size)
        val evidence = FilteredConfirmation()
        return PcmSpeechSession(context, REPLY_SAMPLE_RATE).use { session ->
            checkNotNull(RecordingNoiseFilter.open(REPLY_SAMPLE_RATE, detection = true)).use { filter ->
                session.start()
                var offset = 0
                var sent = 0
                var quiet = false
                // Device recognizers can require some input before they report readiness.
                while (offset < samples.size) {
                    offset = minOf(offset + REPLY_FRAME_SAMPLES, samples.size)
                    filter.processAvailable(samples, offset, clean, evidence::sample)
                    val available = if (filteredRecognition) filter.availableSamples else offset
                    val sending = if (filteredRecognition) clean else samples
                    val wrote = withTimeout(500) {
                        withContext(Dispatchers.IO) { session.write(sending, sent, available) }
                    }
                    if (!wrote) {
                        Log.i("DriveVoiceAudio", "Synthetic confirmation stream stopped at sample $offset")
                        return false
                    }
                    sent = available
                    quiet = evidence.canCloseForSilence(offset * 1000L / REPLY_SAMPLE_RATE)
                    if (quiet) break
                    delay(10)
                }
                if (filteredRecognition) {
                    filter.finish(samples, offset, clean)
                    assertTrue(withTimeout(500) {
                        withContext(Dispatchers.IO) { session.write(clean, sent, offset) }
                    })
                }
                session.endAudio()
                val result = withTimeout(700) { session.result.await() }
                assertTrue("The recognizer reported readiness", session.ready.isCompleted)
                Log.i("DriveVoiceAudio", "Synthetic confirmation: quiet=$quiet, speech=${evidence.possibleSpeech}, " +
                    "clean=${result.clean}, endedAfterEof=${result.endedAfterEof}, " +
                    "startEvent=${session.beginningDetected.get()}, text=${session.textObserved.get()}, samples=$offset")
                evidence.maySend(quiet, result, session.textObserved.get())
            }
        }
    }
}
