package com.drivevoice.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivevoice.app.service.AudioCheck
import com.drivevoice.app.service.AudioCheckFailure
import com.drivevoice.app.service.AudioCheckIssue
import com.drivevoice.app.service.AudioCheckMode
import com.drivevoice.app.service.AudioCheckTrack
import com.drivevoice.app.service.AudioCheckPhase
import com.drivevoice.app.service.AudioEndpoint
import com.drivevoice.app.service.driveAudioGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AudioCheckState(
    val input: AudioEndpoint? = null,
    val output: AudioEndpoint? = null,
    val actualInput: List<AudioEndpoint> = emptyList(),
    val actualOutput: List<AudioEndpoint> = emptyList(),
    val phase: AudioCheckPhase = AudioCheckPhase.IDLE,
    val issue: AudioCheckIssue? = null,
    val finished: Boolean = false,
    val filteredAvailable: Boolean = false,
    val impaired: Boolean = false,
    val level: Float = 0f,
    val volume: Float = 0.5f,
) {
    val active: Boolean get() = phase != AudioCheckPhase.IDLE
}

@HiltViewModel
internal class AudioCheckViewModel @Inject constructor(private val audio: AudioCheck) : ViewModel() {
    private val mutableState = MutableStateFlow(AudioCheckState())
    val state = mutableState.asStateFlow()
    private val finishRequested = MutableStateFlow(false)
    private var job: Job? = null
    private var comparisonRequests: Channel<AudioCheckTrack>? = null
    private var visible = false

    fun devices(allowBluetooth: Boolean) = audio.devices(allowBluetooth)

    fun resume() { visible = true }

    fun pause() {
        visible = false
        stop()
        mutableState.update { it.copy(actualInput = emptyList(), actualOutput = emptyList(), finished = false, issue = null) }
    }

    fun selectInput(input: AudioEndpoint?) {
        mutableState.update { if (it.active) it else it.copy(input = input, actualInput = emptyList(), finished = false, issue = null) }
    }

    fun selectOutput(output: AudioEndpoint?) {
        mutableState.update { if (it.active) it else it.copy(output = output, actualOutput = emptyList(), finished = false, issue = null) }
    }

    fun setVolume(value: Float) { mutableState.update { it.copy(volume = value) } }

    fun finishRecording() {
        if (state.value.phase == AudioCheckPhase.RECORDING) finishRequested.value = true
    }

    fun stop() { job?.cancel() }

    fun play(track: AudioCheckTrack) {
        if (state.value.phase == AudioCheckPhase.COMPARING) comparisonRequests?.trySend(track)
    }

    fun start(mode: AudioCheckMode) {
        val microphone = mode != AudioCheckMode.SPEAKER
        if (!visible || job?.isCompleted == false) return
        if (microphone && !audio.hasMicrophonePermission()) {
            mutableState.update { it.copy(issue = AudioCheckIssue.PERMISSION, finished = false) }
            return
        }
        val reservation = driveAudioGate.tryAcquire()
        if (reservation == null) {
            mutableState.update { it.copy(issue = AudioCheckIssue.BUSY, finished = false) }
            return
        }
        val selected = state.value
        finishRequested.value = false
        val requests = Channel<AudioCheckTrack>()
        comparisonRequests = requests
        mutableState.update {
            it.copy(
                phase = if (microphone) AudioCheckPhase.OPENING_MIC else AudioCheckPhase.OPENING_OUTPUT,
                actualInput = emptyList(), actualOutput = emptyList(), level = 0f, issue = null, finished = false,
                filteredAvailable = false, impaired = false,
            )
        }
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var issue: AudioCheckIssue? = null
            var finished = false
            try {
                audio.run(
                    mode, selected.input?.id, selected.output?.id,
                    finishRecording = { finishRequested.value },
                    nextTrack = { requests.receive() },
                    volume = { state.value.volume },
                    onPhase = { phase -> mutableState.update { it.copy(phase = phase) } },
                    onInput = { devices, level -> mutableState.update { it.copy(actualInput = devices, level = level) } },
                    onOutput = { devices -> mutableState.update { it.copy(actualOutput = devices) } },
                    onComparison = { available, impaired ->
                        mutableState.update { it.copy(filteredAvailable = available, impaired = impaired) }
                    },
                )
                finished = mode == AudioCheckMode.SPEAKER
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AudioCheckFailure) {
                issue = failure.issue
            } catch (_: SecurityException) {
                issue = AudioCheckIssue.PERMISSION
            } catch (_: RuntimeException) {
                issue = when (state.value.phase) {
                    AudioCheckPhase.OPENING_MIC, AudioCheckPhase.RECORDING -> AudioCheckIssue.MIC
                    else -> AudioCheckIssue.OUTPUT
                }
            } finally {
                comparisonRequests = null
                requests.cancel()
                reservation.close()
                mutableState.update {
                    if (visible) it.copy(
                        phase = AudioCheckPhase.IDLE, level = 0f, issue = issue, finished = finished,
                        filteredAvailable = false, impaired = false,
                    )
                    else it.copy(
                        phase = AudioCheckPhase.IDLE, level = 0f, issue = null, finished = false,
                        actualInput = emptyList(), actualOutput = emptyList(),
                        filteredAvailable = false, impaired = false,
                    )
                }
            }
        }
    }
}
