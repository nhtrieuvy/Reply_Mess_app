package com.drivevoice.app.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.drivevoice.app.domain.state.ReplyWindow
import com.drivevoice.app.domain.state.VoiceInput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

    suspend fun checkVietnameseModel(): OfflineSpeechModel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return OfflineSpeechModel.NO_ENGINE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return OfflineSpeechModel.CHECK_UNAVAILABLE
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            val result = CompletableDeferred<OfflineSpeechModel>()
            recognizer.checkRecognitionSupport(recognitionIntent(), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    result.complete(when {
                        support.installedOnDeviceLanguages.hasVietnamese() -> OfflineSpeechModel.READY
                        support.pendingOnDeviceLanguages.hasVietnamese() -> OfflineSpeechModel.PENDING
                        support.supportedOnDeviceLanguages.hasVietnamese() -> OfflineSpeechModel.DOWNLOADABLE
                        else -> OfflineSpeechModel.UNSUPPORTED
                    })
                }

                override fun onError(error: Int) {
                    Log.w("DriveVoiceAudio", "Offline model check error: $error")
                    result.complete(when (error) {
                        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> OfflineSpeechModel.CHECK_UNAVAILABLE
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> OfflineSpeechModel.UNSUPPORTED
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> OfflineSpeechModel.DOWNLOADABLE
                        else -> OfflineSpeechModel.CHECK_FAILED
                    })
                }
            })
            return withTimeoutOrNull(5_000) { result.await() } ?: OfflineSpeechModel.CHECK_FAILED
        } finally {
            recognizer.destroy()
        }
    }

    suspend fun requestVietnameseModel(): OfflineSpeechModel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) return OfflineSpeechModel.NO_ENGINE
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                recognizer.triggerModelDownload(recognitionIntent())
                return OfflineSpeechModel.PENDING
            }
            val result = CompletableDeferred<OfflineSpeechModel>()
            recognizer.triggerModelDownload(recognitionIntent(), context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = Unit
                override fun onSuccess() { result.complete(OfflineSpeechModel.READY) }
                override fun onScheduled() { result.complete(OfflineSpeechModel.PENDING) }
                override fun onError(error: Int) {
                    Log.w("DriveVoiceAudio", "Offline model download error: $error")
                    result.complete(when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> OfflineSpeechModel.UNSUPPORTED
                        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> {
                            try {
                                recognizer.triggerModelDownload(recognitionIntent())
                                OfflineSpeechModel.PENDING
                            } catch (_: RuntimeException) {
                                OfflineSpeechModel.DOWNLOAD_FAILED
                            }
                        }
                        else -> OfflineSpeechModel.DOWNLOAD_FAILED
                    })
                }
            })
            return withTimeoutOrNull(8_000) { result.await() } ?: OfflineSpeechModel.PENDING
        } finally {
            recognizer.destroy()
        }
    }

    private fun List<String>.hasVietnamese(): Boolean =
        any { Locale.forLanguageTag(it.replace('_', '-')).language == "vi" }

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
        val selection = selectReplyMic(
            headsetIds = headsets.map { it.id },
            preferredIds = headsets.filter { it.address == preferredAddress }.map { it.id },
            currentId = audio.communicationDevice?.id,
            phoneId = phone?.id,
            headsetOnly = headsetOnly,
        )
        if (selection !is ReplyMicSelection.Selected || !selection.mic.bluetooth) return selection
        val headset = headsets.first { it.id == selection.mic.id }
        return ReplyMicSelection.Selected(
            selection.mic.copy(address = headset.address.takeIf(BluetoothAdapter::checkBluetoothAddress), type = headset.type),
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            VoiceInput.Unavailable
        } catch (_: IllegalStateException) {
            VoiceInput.Unavailable
        } catch (_: UnsupportedOperationException) {
            VoiceInput.Unavailable
        } catch (_: IllegalArgumentException) {
            VoiceInput.Unavailable
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun findDevice(mic: ReplyMic): AudioDeviceInfo? {
        if (mic.bluetooth && !hasBluetoothPermission(context)) return null
        val available = audio.availableCommunicationDevices
        if (!mic.bluetooth) return available.firstOrNull { it.id == mic.id && it.isPhoneOutput() }
        return available.firstOrNull { it.id == mic.id && it.isHeadset() && (mic.address == null || it.address == mic.address) }
            ?: mic.address?.let { address -> available.firstOrNull { it.address == address && it.type == mic.type } }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun capture(
        window: ReplyWindow,
        mic: ReplyMic,
        buttons: MediaButtonHandler?,
        onAmplitude: (Float?) -> Unit,
    ): VoiceInput = withMicRoute(mic) { selected, _ -> recognize(window, buttons, mic, selected.id, onAmplitude) }
        ?: VoiceInput.Unavailable

    @RequiresApi(Build.VERSION_CODES.S)
    suspend fun <T> withMicRoute(
        mic: ReplyMic,
        capture: suspend (AudioDeviceInfo, () -> Boolean) -> T,
    ): T? {
        if (audio.mode != AudioManager.MODE_NORMAL || audio.isMicrophoneMute) return null
        val selected = findDevice(mic) ?: return null

        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        var acquiredRoute = false
        try {
            acquiredRoute = audio.setCommunicationDevice(selected)
            if (!acquiredRoute) return null
            val connected = withTimeoutOrNull(2_500) {
                while (!routeReady(mic, selected.id)) delay(50)
                true
            }
            if (connected != true) return null
            return capture(selected) { routeReady(mic, selected.id) }
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
        routedId: Int,
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
                if (outcome.isCompleted || !routeReady(mic, routedId)) return@coroutineScope VoiceInput.Unavailable
                val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = engine
                val ready = CompletableDeferred<Unit>()
                engine.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (outcome.isCompleted || !ready.complete(Unit)) return
                        onAmplitude(0f)
                        buttons?.open { outcome.complete(VoiceInput.Button) }
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) {
                        if (ready.isCompleted && !outcome.isCompleted && rmsdB.isFinite()) {
                            onAmplitude(((rmsdB + 2f) / 14f).coerceIn(0f, 1f))
                        }
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        outcome.complete(text?.takeIf { it.isNotBlank() }?.let { VoiceInput.Heard(it) } ?: VoiceInput.Unavailable)
                    }
                    override fun onError(error: Int) {
                        Log.d("DriveVoiceAudio", "Speech recognition callback: ${window.name}, code=$error, ready=${ready.isCompleted}")
                        if (ready.isCompleted && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                error == SpeechRecognizer.ERROR_NO_MATCH)
                        ) {
                            // Keep the explicit button confirmation available until the window closes.
                            return
                        }
                        outcome.complete(VoiceInput.Unavailable)
                    }
                })
                val watch = launch {
                    while (routeReady(mic, routedId)) delay(100)
                    outcome.complete(VoiceInput.Unavailable)
                }
                try {
                    engine.startListening(recognitionIntent())
                    val input = awaitVoiceInput(window, ready, outcome)
                    if (routeReady(mic, routedId)) input else VoiceInput.Unavailable
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
    private fun routeReady(mic: ReplyMic, routedId: Int): Boolean =
        audio.mode == AudioManager.MODE_IN_COMMUNICATION && isMicAvailable(mic) &&
            audio.communicationDevice?.id == routedId

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

        internal fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
}

internal enum class OfflineSpeechModel(val canAttempt: Boolean = false) {
    READY(true), DOWNLOADABLE, PENDING, UNSUPPORTED, NO_ENGINE, CHECK_UNAVAILABLE(true), CHECK_FAILED, DOWNLOAD_FAILED,
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
