package com.drivevoice.app.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.drivevoice.app.data.bluetooth.messageAudioAttributes
import com.drivevoice.app.domain.state.ReplyRecording
import com.drivevoice.app.domain.state.SpeechResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class ReplyRecordingPlayback @Inject constructor(@ApplicationContext context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    suspend fun play(recording: ReplyRecording, headset: String?): SpeechResult = withContext(Dispatchers.IO) {
        val lost = AtomicBoolean()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(messageAudioAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ if (it < 0) lost.set(true) }, Handler(Looper.getMainLooper()))
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return@withContext SpeechResult.FOCUS_UNAVAILABLE
        }
        var allocated: AudioTrack? = null
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(messageAudioAttributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(recording.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(recording.samples.size * 2)
                .build()
            allocated = track
            if (track.write(recording.samples, 0, recording.samples.size, AudioTrack.WRITE_BLOCKING)
                != recording.samples.size
            ) return@withContext SpeechResult.FAILED
            track.setVolume(0f)
            track.play()
            if (withTimeoutOrNull(1_000) {
                    while (!lost.get() && !track.matches(headset)) delay(20)
                    !lost.get()
                } != true
            ) return@withContext SpeechResult.FOCUS_UNAVAILABLE
            track.pause()
            if (track.setPlaybackHeadPosition(0) != AudioTrack.SUCCESS) return@withContext SpeechResult.FAILED
            track.setVolume(1f)
            track.play()
            val timeout = recording.samples.size * 1_000L / recording.sampleRate + 1_500
            withTimeoutOrNull(timeout) {
                while (track.playbackHeadPosition < recording.samples.size) {
                    if (lost.get() || !track.matches(headset)) return@withTimeoutOrNull SpeechResult.FOCUS_UNAVAILABLE
                    delay(10)
                }
                SpeechResult.DONE
            } ?: SpeechResult.FAILED
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            SpeechResult.FAILED
        } finally {
            try {
                allocated?.release()
            } finally {
                audio.abandonAudioFocusRequest(focus)
            }
        }
    }

    private fun AudioTrack.matches(address: String?): Boolean {
        val device = routedDevice ?: return false
        return address == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.address == address)
    }
}
