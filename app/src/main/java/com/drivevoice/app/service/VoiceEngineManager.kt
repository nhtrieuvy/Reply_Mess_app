package com.drivevoice.app.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.drivevoice.app.data.bluetooth.messageAudioAttributes
import com.drivevoice.app.domain.state.SpeechResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/** A single owner calls initialize, speak and close on the main dispatcher. */
internal class VoiceEngineManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = messageAudioAttributes
    private var tts: TextToSpeech? = null

    suspend fun initialize(): Boolean {
        val init = CompletableDeferred<Int>()
        val engine = TextToSpeech(context, { init.complete(it) }, GOOGLE_TTS)
        tts = engine
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { init.await() } != TextToSpeech.SUCCESS) return false
        val voice = engine.voices.orEmpty().filter(Voice::isInstalledVietnamese)
            .sortedWith(compareByDescending<Voice> { it.locale.country == "VN" }.thenByDescending { it.quality })
            .firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
            ?: return false
        val selected = engine.voice ?: return false
        return selected.name == voice.name && selected.isInstalledVietnamese() &&
            engine.setAudioAttributes(attributes) == TextToSpeech.SUCCESS
    }

    suspend fun speak(text: String, onAmplitude: (Float) -> Unit = {}): SpeechResult {
        val engine = tts ?: return SpeechResult.OFFLINE_VOICE_UNAVAILABLE
        if (engine.voice?.isInstalledVietnamese() != true) return SpeechResult.OFFLINE_VOICE_UNAVAILABLE

        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<SpeechResult>()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) result.complete(SpeechResult.FOCUS_UNAVAILABLE)
            }, handler)
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return SpeechResult.FOCUS_UNAVAILABLE
        }

        val envelope = SpeechEnvelope()
        val meter = object : Runnable {
            var startedAt = 0L
            override fun run() {
                if (result.isCompleted) return
                onAmplitude(envelope.at(SystemClock.uptimeMillis() - startedAt))
                handler.postDelayed(this, 33)
            }
        }
        var completed = false
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    if (utteranceId == id) handler.post {
                        if (!result.isCompleted) {
                            meter.startedAt = SystemClock.uptimeMillis()
                            meter.run()
                        }
                    }
                }

                override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    if (utteranceId == id && audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                        envelope.configure(sampleRateInHz, channelCount)
                    }
                }

                override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                    if (utteranceId == id && !result.isCompleted) envelope.append(audio)
                }

                override fun onDone(utteranceId: String) {
                    if (utteranceId == id) result.complete(SpeechResult.DONE)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String) {
                    if (utteranceId == id) result.complete(SpeechResult.FAILED)
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    if (utteranceId != id) return
                    val failed = when (errorCode) {
                        TextToSpeech.ERROR_NOT_INSTALLED_YET, TextToSpeech.ERROR_NETWORK,
                        TextToSpeech.ERROR_NETWORK_TIMEOUT -> SpeechResult.OFFLINE_VOICE_UNAVAILABLE
                        else -> SpeechResult.FAILED
                    }
                    result.complete(failed)
                }

                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    if (utteranceId == id) result.complete(SpeechResult.FAILED)
                }
            })
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                return SpeechResult.FAILED
            }
            val outcome = withTimeoutOrNull(UTTERANCE_TIMEOUT_MS) { result.await() } ?: SpeechResult.FAILED
            completed = outcome == SpeechResult.DONE
            return outcome
        } finally {
            result.cancel()
            handler.removeCallbacks(meter)
            onAmplitude(0f)
            try {
                engine.setOnUtteranceProgressListener(null)
                if (!completed) engine.stop()
            } finally {
                audio.abandonAudioFocusRequest(focus)
            }
        }
    }

    fun close() {
        val engine = tts ?: return
        tts = null
        try {
            engine.stop()
        } finally {
            engine.shutdown()
        }
    }

    private companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        const val INIT_TIMEOUT_MS = 8_000L
        const val UTTERANCE_TIMEOUT_MS = 75_000L
    }
}

/** Retains amplitude frames only. Engines can synthesize PCM well ahead of playback. */
private class SpeechEnvelope {
    private val levels = FloatArray(2_250)
    private var frames = 0
    private var frameSamples = 0
    private var samples = 0
    private var sum = 0L

    @Synchronized
    fun configure(sampleRate: Int, channels: Int) {
        frameSamples = (sampleRate * channels / 30).coerceAtLeast(1)
    }

    @Synchronized
    fun append(audio: ByteArray) {
        if (frameSamples == 0) return
        for (index in 1 until audio.size step 2) {
            if (frames == levels.size) return
            val sample = audio[index].toInt()
            sum += sample * sample
            samples++
            if (samples == frameSamples) {
                levels[frames++] = level()
                samples = 0
                sum = 0
            }
        }
    }

    @Synchronized
    fun at(elapsedMs: Long): Float {
        val index = (elapsedMs * 30 / 1_000).toInt()
        return if (index < frames) levels[index] else level()
    }

    private fun level(): Float = if (samples == 0) 0f else (sqrt(sum.toFloat() / samples) / 48f).coerceIn(0f, 1f)
}

private fun Voice.isInstalledVietnamese(): Boolean =
    locale.language == "vi" && !isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()
