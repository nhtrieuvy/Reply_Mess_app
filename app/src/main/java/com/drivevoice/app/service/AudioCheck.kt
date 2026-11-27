package com.drivevoice.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AudioEndpoint(val id: Int, val name: String, val type: Int) {
    companion object {
        fun from(device: AudioDeviceInfo) = AudioEndpoint(device.id, device.productName.toString().trim(), device.type)
    }
}

internal data class AudioCheckDevices(
    val inputs: List<AudioEndpoint> = emptyList(),
    val outputs: List<AudioEndpoint> = emptyList(),
)

internal enum class AudioCheckPhase { IDLE, OPENING_MIC, RECORDING, OPENING_OUTPUT, PLAYING, COMPARING }
internal enum class AudioCheckMode { SPEAKER, MICROPHONE, SENTENCE }
internal enum class AudioCheckTrack { RAW, FILTERED }
internal enum class AudioCheckIssue { BUSY, PERMISSION, MIC, OUTPUT, DISCONNECTED, FOCUS, FILTER, UNCLEAR }

internal class AudioCheckFailure(val issue: AudioCheckIssue) : Exception()

internal class AudioCheck @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun hasMicrophonePermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun devices(allowBluetooth: Boolean) = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { trySend(Unit) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { trySend(Unit) }
        }
        audio.registerAudioDeviceCallback(callback, handler)
        trySend(Unit)
        awaitClose { audio.unregisterAudioDeviceCallback(callback) }
    }.map {
        AudioCheckDevices(
            inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.isSupportedInput(allowBluetooth) }.map(AudioEndpoint::from),
            outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSupportedOutput() }.map(AudioEndpoint::from),
        )
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun run(
        mode: AudioCheckMode,
        inputId: Int?,
        outputId: Int?,
        finishRecording: () -> Boolean,
        nextTrack: suspend () -> AudioCheckTrack,
        volume: () -> Float,
        onPhase: (AudioCheckPhase) -> Unit,
        onInput: (List<AudioEndpoint>, Float) -> Unit,
        onOutput: (List<AudioEndpoint>) -> Unit,
        onComparison: (filtered: Boolean, impaired: Boolean) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val requestedOutput = outputId?.let { id ->
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                ?: fail(AudioCheckIssue.DISCONNECTED)
        }
        var clip: AudioClip? = null
        try {
            val captured = if (mode == AudioCheckMode.SPEAKER) {
                AudioClip(makeTestTone(), TONE_FRAMES, TONE_RATE)
            } else {
                onPhase(AudioCheckPhase.OPENING_MIC)
                withFocus { focusLost ->
                    capture(mode, inputId, focusLost, finishRecording, onPhase, onInput)
                }
            }
            clip = captured

            suspend fun replay(selected: AudioClip) {
                onPhase(AudioCheckPhase.OPENING_OUTPUT)
                withFocus { focusLost ->
                    val ready = awaitReplyPlaybackRoute {
                        checkFocus(focusLost)
                        audio.mode == AudioManager.MODE_NORMAL &&
                            (requestedOutput == null || findOutput(requestedOutput) != null)
                    }
                    if (!ready) {
                        fail(if (requestedOutput != null && findOutput(requestedOutput) == null) AudioCheckIssue.DISCONNECTED
                        else AudioCheckIssue.OUTPUT)
                    }
                    play(selected, requestedOutput, focusLost, volume, onPhase, onOutput)
                }
            }

            replay(captured)
            if (mode == AudioCheckMode.SPEAKER) return@withContext
            val filtered = captured.filtered
            onComparison(filtered != null, captured.impaired)
            while (true) {
                onPhase(AudioCheckPhase.COMPARING)
                val track = withTimeoutOrNull(60_000) { nextTrack() } ?: break
                when (track) {
                    AudioCheckTrack.RAW -> replay(captured)
                    AudioCheckTrack.FILTERED -> if (filtered != null) replay(captured.copy(samples = filtered))
                }
            }
        } finally {
            clip?.clear()
        }
    }

    private suspend fun <T> withFocus(block: suspend (AtomicBoolean) -> T): T {
        val focusLost = AtomicBoolean()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ if (it < 0) focusLost.set(true) }, handler)
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) fail(AudioCheckIssue.FOCUS)
        return try {
            block(focusLost)
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture(
        mode: AudioCheckMode,
        inputId: Int?,
        focusLost: AtomicBoolean,
        finish: () -> Boolean,
        onPhase: (AudioCheckPhase) -> Unit,
        onInput: (List<AudioEndpoint>, Float) -> Unit,
    ): AudioClip {
        checkMicrophone()
        if (audio.mode != AudioManager.MODE_NORMAL) fail(AudioCheckIssue.FOCUS)
        val requested = inputId?.let { id ->
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
                ?: fail(AudioCheckIssue.DISCONNECTED)
        }
        val bluetooth = requested?.takeIf { it.isBluetoothMicrophone() }
        if (bluetooth != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) fail(AudioCheckIssue.MIC)
        var communicationRequested = false
        var clip: AudioClip? = null
        var handedOff = false
        try {
            try {
                if (bluetooth != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED
                    ) fail(AudioCheckIssue.PERMISSION)
                    if (audio.mode != AudioManager.MODE_NORMAL) fail(AudioCheckIssue.FOCUS)
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    communicationRequested = true
                    openBluetoothMic(bluetooth, focusLost)
                }

                val capture = makeRecorder(bluetooth != null)
                val recorder = capture.recorder
                val sampleRate = capture.sampleRate
                val sentence = if (mode == AudioCheckMode.SENTENCE) SentenceEndpointer() else null
                val captureMs = if (sentence == null) AUDIO_CHECK_DURATION_MS else SentenceEndpointer.MAX_MS.toLong()
                val samples = ShortArray(
                    if (sentence == null) audioCheckMaxFrames(sampleRate) else sampleRate * SentenceEndpointer.MAX_MS / 1_000,
                )
                var filter = RecordingNoiseFilter.open(sampleRate, detection = sentence != null)
                val filtered = filter?.let { ShortArray(samples.size) }
                clip = AudioClip(samples, 0, sampleRate, filtered = filtered)
                val block = ShortArray(sampleRate / 50)
                var endState = SentenceState.LISTENING
                val onFrame: ((FrameEvidence) -> Unit)? = sentence?.let { detector ->
                    { frame -> endState = detector.add(frame) }
                }
                fun disableFilter() {
                    filter?.close()
                    filter = null
                    filtered?.fill(0)
                    if (sentence != null) fail(AudioCheckIssue.FILTER)
                }
                try {
                    if (sentence != null && filter == null) fail(AudioCheckIssue.FILTER)
                    val preferred = requested?.let { device ->
                        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { matchesInput(device, it) }
                            ?: fail(AudioCheckIssue.DISCONNECTED)
                    }
                    if (preferred != null && !recorder.setPreferredDevice(preferred)) fail(AudioCheckIssue.MIC)
                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) fail(AudioCheckIssue.MIC)

                    var routed = emptyList<AudioDeviceInfo>()
                    val routedReady = withTimeoutOrNull(1_500) {
                        while (!inputRouteMatches(requested, routed)) {
                            checkCapture(requested, recorder, focusLost)
                            if (recorder.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING) < 0) fail(AudioCheckIssue.MIC)
                            val actual = if (Build.VERSION.SDK_INT >= 36) recorder.routedDevices else listOfNotNull(recorder.routedDevice)
                            if (routed != actual) onInput(actual.map(AudioEndpoint::from), 0f)
                            routed = actual
                            if (!inputRouteMatches(requested, routed)) delay(15)
                        }
                        true
                    }
                    if (routedReady != true) fail(AudioCheckIssue.MIC)

                    val connectedIds = routed.map { it.id }.toSet()
                    val endpoints = routed.map(AudioEndpoint::from)
                    onInput(endpoints, 0f)
                    onPhase(AudioCheckPhase.RECORDING)
                    var count = 0
                    withTimeoutOrNull(captureMs) {
                        while (!finish() && count < samples.size && endState == SentenceState.LISTENING) {
                            checkCapture(requested, recorder, focusLost)
                            val current = if (Build.VERSION.SDK_INT >= 36) recorder.routedDevices else listOfNotNull(recorder.routedDevice)
                            if (!audioCheckRouteMatches(connectedIds, current.map { it.id }.toSet())) {
                                val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                fail(if (routed.any { old -> inputs.none { it.id == old.id } }) AudioCheckIssue.DISCONNECTED else AudioCheckIssue.MIC)
                            }
                            val read = recorder.read(block, 0, minOf(block.size, samples.size - count), AudioRecord.READ_NON_BLOCKING)
                            if (read < 0) fail(AudioCheckIssue.MIC)
                            if (read > 0) {
                                block.copyInto(samples, count, endIndex = read)
                                count += read
                                onInput(endpoints, audioCheckLevel(block, read))
                                if (filtered != null) {
                                    try {
                                        filter?.processAvailable(samples, count, filtered, onFrame)
                                    } catch (_: IllegalStateException) {
                                        disableFilter()
                                    }
                                }
                            }
                            delay(10)
                        }
                    }
                    checkCapture(requested, recorder, focusLost)
                    if (count == 0) fail(AudioCheckIssue.MIC)
                    if (sentence != null && endState == SentenceState.LISTENING && finish()) {
                        endState = sentence.finishManually()
                    }
                    if (sentence != null && endState != SentenceState.COMPLETE) fail(AudioCheckIssue.UNCLEAR)
                    if (filtered != null) {
                        try {
                            filter?.finish(samples, count, filtered)
                        } catch (_: IllegalStateException) {
                            disableFilter()
                        }
                    }
                    val bounds = sentence?.bounds
                    val start = bounds?.let { it.startMs * sampleRate / 1_000 } ?: 0
                    val end = bounds?.let { (it.endMs * sampleRate / 1_000).coerceAtMost(count) } ?: count
                    clip = AudioClip(
                        samples, end - start, sampleRate, start, filtered.takeIf { filter != null },
                        impaired = filter?.impaired == true,
                    )
                } finally {
                    filter?.close()
                    block.fill(0)
                    try {
                        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                    } finally {
                        recorder.release()
                    }
                }
            } finally {
                if (communicationRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        audio.clearCommunicationDevice()
                    } finally {
                        audio.mode = AudioManager.MODE_NORMAL
                    }
                }
            }
            return requireNotNull(clip).also { handedOff = true }
        } finally {
            if (!handedOff) clip?.clear()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun openBluetoothMic(device: AudioDeviceInfo, focusLost: AtomicBoolean) {
        val preferred = audio.availableCommunicationDevices.firstOrNull { output ->
            output.type == device.type && output.address == device.address
        } ?: fail(AudioCheckIssue.MIC)
        if (!audio.setCommunicationDevice(preferred)) fail(AudioCheckIssue.MIC)
        val ready = withTimeoutOrNull(2_500) {
            while (audio.communicationDevice?.id != preferred.id) {
                checkFocus(focusLost)
                if (audio.availableCommunicationDevices.none { it.id == preferred.id }) fail(AudioCheckIssue.DISCONNECTED)
                delay(50)
            }
            true
        }
        if (ready != true) fail(AudioCheckIssue.MIC)
    }

    private suspend fun checkCapture(requested: AudioDeviceInfo?, recorder: AudioRecord, focusLost: AtomicBoolean) {
        checkFocus(focusLost)
        checkMicrophone()
        if (requested != null && audio.getDevices(AudioManager.GET_DEVICES_INPUTS).none { matchesInput(requested, it) }) {
            fail(AudioCheckIssue.DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recorder.activeRecordingConfiguration?.isClientSilenced == true) {
            fail(AudioCheckIssue.MIC)
        }
    }

    private fun checkMicrophone() {
        if (!hasMicrophonePermission()) fail(AudioCheckIssue.PERMISSION)
        if (audio.isMicrophoneMute) fail(AudioCheckIssue.MIC)
    }

    @SuppressLint("MissingPermission")
    private fun makeRecorder(bluetooth: Boolean): RecorderCapture {
        val source = if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        for (rate in listOf(48_000, 16_000)) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val recorder = try {
                AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(format(rate, AudioFormat.CHANNEL_IN_MONO))
                    .setBufferSizeInBytes(maxOf(minimum, rate / 5)).build()
            } catch (_: UnsupportedOperationException) {
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return RecorderCapture(recorder, rate)
            recorder.release()
        }
        fail(AudioCheckIssue.MIC)
    }

    private suspend fun play(
        clip: AudioClip,
        requested: AudioDeviceInfo?,
        focusLost: AtomicBoolean,
        volume: () -> Float,
        onPhase: (AudioCheckPhase) -> Unit,
        onOutput: (List<AudioEndpoint>) -> Unit,
    ) {
        val minimum = AudioTrack.getMinBufferSize(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) fail(AudioCheckIssue.OUTPUT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(format(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(maxOf(minimum, clip.sampleRate / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) fail(AudioCheckIssue.OUTPUT)
            val preferred = requested?.let { findOutput(it) ?: fail(AudioCheckIssue.DISCONNECTED) }
            if (preferred != null && !track.setPreferredDevice(preferred)) fail(AudioCheckIssue.OUTPUT)
            track.setVolume(0f)
            val chunk = clip.sampleRate / 50
            // Some devices restart only after the AudioTrack buffer refills following an underrun.
            val queueTarget = maxOf(track.bufferSizeInFrames, chunk)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                track.setStartThresholdInFrames(minOf(chunk, track.bufferSizeInFrames))
            }
            val silence = ShortArray(queueTarget)
            var written = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) fail(AudioCheckIssue.OUTPUT)
            track.play()
            var routed = emptyList<AudioDeviceInfo>()
            val verified = withTimeoutOrNull(2_000) {
                while (true) {
                    checkFocus(focusLost)
                    if (requested != null && findOutput(requested) == null) fail(AudioCheckIssue.DISCONNECTED)
                    val actual = track.currentOutputs()
                    if (routed != actual) onOutput(actual.map(AudioEndpoint::from))
                    routed = actual
                    if (audioCheckRouteMatches(preferred?.id?.let(::setOf), routed.map { it.id }.toSet())) break
                    val size = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (size < 0) fail(AudioCheckIssue.OUTPUT)
                    written += size
                    delay(20)
                }
                true
            }
            if (verified != true) fail(AudioCheckIssue.OUTPUT)
            val expected = routed.map { it.id }.toSet()
            onPhase(AudioCheckPhase.PLAYING)
            val finished = withTimeoutOrNull(AUDIO_CHECK_DURATION_MS + 2_000) {
                var position = 0
                var appliedVolume = 0f
                while (position < clip.count || track.playbackHeadPosition < written) {
                    checkFocus(focusLost)
                    val actual = track.currentOutputs().map { it.id }.toSet()
                    if (!audioCheckRouteMatches(expected, actual)) {
                        val available = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()
                        fail(if (!available.containsAll(expected)) AudioCheckIssue.DISCONNECTED else AudioCheckIssue.OUTPUT)
                    }
                    val nextVolume = volume().coerceIn(0f, 1f)
                    if (nextVolume != appliedVolume) {
                        track.setVolume(nextVolume)
                        appliedVolume = nextVolume
                    }
                    if (position < clip.count && written - track.playbackHeadPosition < queueTarget) {
                        val size = track.write(clip.samples, clip.start + position, minOf(chunk, clip.count - position), AudioTrack.WRITE_NON_BLOCKING)
                        if (size < 0) fail(AudioCheckIssue.OUTPUT)
                        position += size
                        written += size
                    }
                    delay(10)
                }
                true
            }
            if (finished != true) {
                Log.w("DriveVoiceAudio", "Audio test playback stalled: queued=$written played=${track.playbackHeadPosition}")
                fail(AudioCheckIssue.OUTPUT)
            }
        } finally {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.setVolume(0f)
                    track.pause()
                    track.flush()
                }
            } finally {
                track.release()
            }
        }
    }

    private suspend fun checkFocus(lost: AtomicBoolean) {
        currentCoroutineContext().ensureActive()
        if (lost.get()) fail(AudioCheckIssue.FOCUS)
    }

    private fun findOutput(expected: AudioDeviceInfo): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { actual ->
            matchesDevice(expected, actual, expected.type in wirelessMediaTypes)
        }

    private fun AudioTrack.currentOutputs(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 36) routedDevices else listOfNotNull(routedDevice)

    private fun inputRouteMatches(expected: AudioDeviceInfo?, actual: List<AudioDeviceInfo>): Boolean =
        if (expected == null) actual.isNotEmpty() else actual.singleOrNull()?.let { matchesInput(expected, it) } == true

    private fun matchesInput(expected: AudioDeviceInfo, actual: AudioDeviceInfo): Boolean =
        matchesDevice(expected, actual, expected.isBluetoothMicrophone())

    private fun matchesDevice(expected: AudioDeviceInfo, actual: AudioDeviceInfo, wireless: Boolean): Boolean {
        if (expected.type != actual.type) return false
        if (wireless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && expected.address.isNotBlank()) {
            return expected.address == actual.address
        }
        return expected.id == actual.id
    }

    @SuppressLint("InlinedApi")
    private fun AudioDeviceInfo.isBluetoothMicrophone() =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    @SuppressLint("InlinedApi")
    private fun AudioDeviceInfo.isSupportedInput(allowBluetooth: Boolean) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && allowBluetooth
        else -> false
    }

    @SuppressLint("InlinedApi")
    private fun AudioDeviceInfo.isSupportedOutput() = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> true
        else -> false
    }

    private fun format(rate: Int, channels: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(channels).build()

    private fun makeTestTone() = ShortArray(TONE_FRAMES) { frame ->
        val fade = minOf(frame, TONE_FRAMES - frame - 1, TONE_RATE / 50) / (TONE_RATE / 50f)
        (8_000 * fade * sin(2 * PI * 540 * frame / TONE_RATE)).toInt().toShort()
    }

    private fun fail(issue: AudioCheckIssue): Nothing = throw AudioCheckFailure(issue)

    private data class RecorderCapture(val recorder: AudioRecord, val sampleRate: Int)
    private data class AudioClip(
        val samples: ShortArray,
        val count: Int,
        val sampleRate: Int,
        val start: Int = 0,
        val filtered: ShortArray? = null,
        val impaired: Boolean = false,
    ) {
        fun clear() {
            samples.fill(0)
            filtered?.fill(0)
        }
    }

    private companion object {
        @SuppressLint("InlinedApi")
        val wirelessMediaTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
        const val TONE_RATE = 48_000
        const val TONE_FRAMES = TONE_RATE / 2
    }
}

internal const val AUDIO_CHECK_DURATION_MS = 5_000L

internal fun audioCheckMaxFrames(sampleRate: Int) = (sampleRate * AUDIO_CHECK_DURATION_MS / 1_000).toInt()

internal fun audioCheckLevel(samples: ShortArray, count: Int): Float {
    if (count == 0) return 0f
    var squared = 0.0
    for (index in 0 until count) {
        val sample = samples[index] / 32_768.0
        squared += sample * sample
    }
    if (squared == 0.0) return 0f
    val decibels = 10 * log10(squared / count)
    return ((decibels + 60) / 60).coerceIn(0.0, 1.0).toFloat()
}

internal fun audioCheckRouteMatches(expected: Set<Int>?, actual: Set<Int>) =
    actual.isNotEmpty() && (expected == null || actual == expected)
