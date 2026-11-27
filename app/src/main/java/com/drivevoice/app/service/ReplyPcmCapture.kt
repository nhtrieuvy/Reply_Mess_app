package com.drivevoice.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.drivevoice.app.domain.state.ReplyRecording
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val REPLY_SAMPLE_RATE = 16_000
internal const val REPLY_FRAME_SAMPLES = REPLY_SAMPLE_RATE / 100

internal class ReplyPcmCapture @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @RequiresApi(Build.VERSION_CODES.S)
    suspend fun record(
        device: AudioDeviceInfo,
        routeReady: () -> Boolean,
        filtered: Boolean,
        stop: AtomicBoolean,
        onAmplitude: (Float?) -> Unit,
    ): ReplyRecording? {
        var captured: ReplyRecording? = null
        var returned = false
        try {
            val result = withTimeoutOrNull(SentenceEndpointer.MAX_MS + 1_500L) {
                recordUtterance(device, routeReady, filtered, stop, onAmplitude) { captured = it }
            }
            returned = result != null
            return result
        } finally {
            if (!returned) captured?.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun recordUtterance(
        device: AudioDeviceInfo,
        routeReady: () -> Boolean,
        filtered: Boolean,
        stop: AtomicBoolean,
        onAmplitude: (Float?) -> Unit,
        onCaptured: (ReplyRecording) -> Unit,
    ): ReplyRecording? =
        useMic(device, routeReady) { mic ->
            val filter = RecordingNoiseFilter.open(REPLY_SAMPLE_RATE, detection = true) ?: return@useMic null
            val raw = ShortArray(REPLY_SAMPLE_RATE * SentenceEndpointer.MAX_MS / 1_000)
            val cleaned = ShortArray(raw.size)
            val detector = SentenceEndpointer()
            var state = SentenceState.LISTENING
            var clipped = false
            var count = 0
            try {
                while (!stop.get() && count < raw.size && state == SentenceState.LISTENING) {
                    val frame = mic.nextFrame() ?: return@useMic null
                    frame.copyInto(raw, count)
                    count += frame.size
                    filter.processAvailable(raw, count, cleaned) {
                        clipped = clipped || it.clipped
                        state = detector.add(it)
                    }
                    onAmplitude(audioCheckLevel(frame, frame.size))
                }
                if (stop.get()) state = detector.finishManually()
                val bounds = detector.bounds ?: return@useMic null
                if (state != SentenceState.COMPLETE || clipped || !mic.valid()) return@useMic null
                filter.finish(raw, count, cleaned)
                val start = bounds.startMs * REPLY_SAMPLE_RATE / 1_000
                val end = minOf(count, bounds.endMs * REPLY_SAMPLE_RATE / 1_000)
                if (end <= start) return@useMic null
                ReplyRecording((if (filtered) cleaned else raw).copyOfRange(start, end), REPLY_SAMPLE_RATE)
                    .also(onCaptured)
            } finally {
                onAmplitude(null)
                raw.fill(0)
                cleaned.fill(0)
                filter.close()
            }
        }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    suspend fun <T> useMic(
        device: AudioDeviceInfo,
        routeReady: () -> Boolean,
        capture: suspend (PcmMicrophone) -> T,
    ): T? = withContext(Dispatchers.IO) {
        if (!SpeechRecognitionManager.hasPermissions(context) || !routeReady()) return@withContext null
        val lost = AtomicBoolean()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ if (it < 0) lost.set(true) }, handler)
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@withContext null
        var recorder: AudioRecord? = null
        var mic: PcmMicrophone? = null
        try {
            val min = AudioRecord.getMinBufferSize(REPLY_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@withContext null
            val recording = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(REPLY_SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min, REPLY_FRAME_SAMPLES * 16))
                .build()
            recorder = recording
            if (recording.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                audio.getDevices(AudioManager.GET_DEVICES_INPUTS).singleOrNull { device.matchesInput(it) }?.let {
                    if (!recording.setPreferredDevice(it)) return@withContext null
                }
            }
            recording.startRecording()
            if (recording.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
            val source = PcmMicrophone(recording, device) {
                !lost.get() && !audio.isMicrophoneMute && SpeechRecognitionManager.hasPermissions(context) && routeReady()
            }
            mic = source
            if (!source.awaitRoute()) return@withContext null
            capture(source)
        } finally {
            mic?.clear()
            try {
                recorder?.let {
                    try {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                    } finally {
                        it.release()
                    }
                }
            } finally {
                audio.abandonAudioFocusRequest(focus)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
internal class PcmMicrophone(
    private val recorder: AudioRecord,
    private val selected: AudioDeviceInfo,
    private val contextValid: () -> Boolean,
) {
    private val buffer = ShortArray(REPLY_FRAME_SAMPLES)

    fun valid(): Boolean =
        contextValid() && recorder.routedDevice?.let(selected::matchesInput) == true &&
            recorder.activeRecordingConfiguration?.isClientSilenced != true

    suspend fun awaitRoute(): Boolean = withTimeoutOrNull(1_000) {
        while (contextValid()) {
            currentCoroutineContext().ensureActive()
            if (recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING) < 0) return@withTimeoutOrNull false
            if (valid()) return@withTimeoutOrNull true
            delay(10)
        }
        false
    } == true

    suspend fun nextFrame(): ShortArray? {
        val end = SystemClock.elapsedRealtime() + 250
        var count = 0
        while (count < buffer.size) {
            currentCoroutineContext().ensureActive()
            if (!valid() || SystemClock.elapsedRealtime() >= end) return null
            val read = recorder.read(buffer, count, buffer.size - count, AudioRecord.READ_NON_BLOCKING)
            if (read < 0) return null
            if (read == 0) delay(5) else count += read
        }
        return buffer
    }

    fun clear() { buffer.fill(0) }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun AudioDeviceInfo.matchesInput(actual: AudioDeviceInfo): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET ->
        actual.type == type && address.isNotBlank() && actual.address == address
    else -> actual.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
}
