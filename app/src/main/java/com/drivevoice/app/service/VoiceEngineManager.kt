package com.drivevoice.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SpeechResult { DONE, FOCUS_UNAVAILABLE, OFFLINE_VOICE_UNAVAILABLE, FAILED }

/** A single owner calls initialize, speak and close on the main dispatcher. */
internal class VoiceEngineManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
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

    suspend fun speak(text: String, onSpeaking: () -> Unit = {}): SpeechResult {
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

        var completed = false
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    if (utteranceId == id) handler.post {
                        if (!result.isCompleted) onSpeaking()
                    }
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

private fun Voice.isInstalledVietnamese(): Boolean =
    locale.language == "vi" && !isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()
