package com.drivevoice.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.drivevoice.app.domain.state.ReplyWindow
import com.drivevoice.app.domain.state.VoiceInput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Used only on the main dispatcher and only after the service acquires microphone eligibility. */
internal class SpeechRecognitionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        hasPermissions(context) && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun hasVietnameseModel(): Boolean {
        if (!isAvailable()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            val supported = CompletableDeferred<Boolean>()
            recognizer.checkRecognitionSupport(recognitionIntent(), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    supported.complete(support.installedOnDeviceLanguages.any { Locale.forLanguageTag(it).language == "vi" })
                }

                override fun onError(error: Int) {
                    // Some on-device engines implement recognition without the optional support query.
                    supported.complete(error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
                }
            })
            return withTimeoutOrNull(3_000) { supported.await() } == true
        } finally {
            recognizer.destroy()
        }
    }

    fun selectMic(
        preferredAddress: String?,
        headsetOnly: Boolean,
        requiredAddress: String?,
    ): ReplyMicSelection {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !hasPermissions(context)) {
            return ReplyMicSelection.Unavailable
        }
        val available = audio.availableCommunicationDevices
        val headsets = if (hasBluetoothPermission(context)) {
            available.filter { it.isHeadset() && (requiredAddress == null || it.address == requiredAddress) }
        } else emptyList()
        val phone = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        return selectReplyMic(
            headsetIds = headsets.map { it.id },
            preferredIds = headsets.filter { it.address == preferredAddress }.map { it.id },
            currentId = audio.communicationDevice?.id,
            phoneId = phone?.id,
            headsetOnly = headsetOnly,
        )
    }

    fun isMicAvailable(mic: ReplyMic): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasPermissions(context) &&
            !audio.isMicrophoneMute && findDevice(mic) != null

    suspend fun listen(
        window: ReplyWindow,
        mic: ReplyMic,
        buttons: MediaButtonHandler?,
        onAmplitude: (Float?) -> Unit,
    ): VoiceInput {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isAvailable()) return VoiceInput.Unavailable
        return try {
            capture(window, mic, buttons, onAmplitude)
        } catch (_: SecurityException) {
            VoiceInput.Unavailable
        } catch (_: IllegalStateException) {
            VoiceInput.Unavailable
        } catch (_: UnsupportedOperationException) {
            VoiceInput.Unavailable
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun findDevice(mic: ReplyMic): AudioDeviceInfo? {
        if (mic.bluetooth && !hasBluetoothPermission(context)) return null
        return audio.availableCommunicationDevices.firstOrNull {
            it.id == mic.id && (if (mic.bluetooth) it.isHeadset() else it.isPhoneOutput())
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun capture(
        window: ReplyWindow,
        mic: ReplyMic,
        buttons: MediaButtonHandler?,
        onAmplitude: (Float?) -> Unit,
    ): VoiceInput {
        if (audio.mode != AudioManager.MODE_NORMAL || audio.isMicrophoneMute) return VoiceInput.Unavailable
        val selected = findDevice(mic) ?: return VoiceInput.Unavailable

        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        var acquiredRoute = false
        try {
            acquiredRoute = audio.setCommunicationDevice(selected)
            if (!acquiredRoute) return VoiceInput.Unavailable
            val connected = withTimeoutOrNull(2_500) {
                while (audio.communicationDevice?.id != selected.id) delay(50)
                true
            }
            if (connected != true || !routeReady(mic)) return VoiceInput.Unavailable
            return recognize(window, buttons, mic, onAmplitude)
        } finally {
            try {
                if (acquiredRoute) audio.clearCommunicationDevice()
            } finally {
                audio.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun recognize(
        window: ReplyWindow,
        buttons: MediaButtonHandler?,
        mic: ReplyMic,
        onAmplitude: (Float?) -> Unit,
    ): VoiceInput =
        coroutineScope {
            val outcome = CompletableDeferred<VoiceInput>()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ if (it < 0) outcome.complete(VoiceInput.Unavailable) }, handler)
                .build()
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                return@coroutineScope VoiceInput.Unavailable
            }

            var recognizer: SpeechRecognizer? = null
            try {
                playTone(AudioManager.STREAM_VOICE_CALL, ToneGenerator.TONE_PROP_BEEP)
                if (outcome.isCompleted || !routeReady(mic)) return@coroutineScope VoiceInput.Unavailable
                val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = engine
                var ready = false
                var heardSpeech = false
                var silenceReported = false
                engine.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (ready || outcome.isCompleted) return
                        ready = true
                        onAmplitude(0f)
                        buttons?.open { outcome.complete(VoiceInput.Button) }
                    }
                    override fun onBeginningOfSpeech() { heardSpeech = true }
                    override fun onRmsChanged(rmsdB: Float) {
                        if (ready && !outcome.isCompleted && rmsdB.isFinite()) {
                            onAmplitude(((rmsdB + 2f) / 14f).coerceIn(0f, 1f))
                        }
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).isNullOrEmpty()) {
                            heardSpeech = true
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        outcome.complete(text?.takeIf { it.isNotBlank() }?.let { VoiceInput.Heard(it) } ?: VoiceInput.Unavailable)
                    }
                    override fun onError(error: Int) {
                        if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT && ready && !heardSpeech) {
                            silenceReported = true
                        } else {
                            outcome.complete(VoiceInput.Unavailable)
                        }
                    }
                })
                val watch = launch {
                    while (routeReady(mic)) delay(100)
                    outcome.complete(VoiceInput.Unavailable)
                }
                try {
                    engine.startListening(recognitionIntent())
                    val input = withTimeoutOrNull(window.timeoutMs) { outcome.await() }
                    when {
                        !routeReady(mic) -> VoiceInput.Unavailable
                        input != null -> input
                        silenceReported && !heardSpeech -> VoiceInput.Silence
                        ready -> VoiceInput.Timeout
                        else -> VoiceInput.Unavailable
                    }
                } finally {
                    watch.cancel()
                }
            } finally {
                buttons?.endWindow()
                outcome.cancel()
                onAmplitude(null)
                try {
                    try {
                        recognizer?.cancel()
                    } finally {
                        recognizer?.destroy()
                    }
                } finally {
                    audio.abandonAudioFocusRequest(focus)
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun routeReady(mic: ReplyMic): Boolean =
        audio.mode == AudioManager.MODE_IN_COMMUNICATION && isMicAvailable(mic) &&
            audio.communicationDevice?.id == mic.id

    @RequiresApi(Build.VERSION_CODES.S)
    private fun AudioDeviceInfo.isHeadset(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun AudioDeviceInfo.isPhoneOutput(): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    companion object {
        fun hasPermissions(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        @RequiresApi(Build.VERSION_CODES.S)
        private fun hasBluetoothPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
}

internal suspend fun playTone(stream: Int, tone: Int) {
    val generator = try {
        ToneGenerator(stream, 45)
    } catch (_: RuntimeException) {
        return
    }
    try {
        if (generator.startTone(tone, 120)) delay(180)
    } finally {
        generator.release()
    }
}
