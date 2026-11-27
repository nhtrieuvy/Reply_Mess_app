package com.drivevoice.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import com.drivevoice.app.domain.state.ReplyWindow
import com.drivevoice.app.domain.state.VoiceInput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class ReplyPcmEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capture: ReplyPcmCapture,
    private val voice: VoiceEngineManager,
) {
    private val preferences = context.getSharedPreferences("offline_pcm_compatibility", Context.MODE_PRIVATE)

    fun isVerified(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val current = runCatching { profile() }.getOrNull() ?: return false
        return preferences.getString(VERIFIED, null) == current
    }

    suspend fun verify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognitionManager.hasPermissions(context) ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) return false
        val reservation = driveAudioGate.tryAcquire() ?: return false
        try {
            val identity = profile() ?: return false
            preferences.edit().remove(VERIFIED).apply()
            if (!voice.initialize()) return false
            val phrase = listOf("Kiểm tra âm thanh tiếng Việt", "Hẹn gặp lại vào ngày mai").random()
            val sample = voice.synthesizeProbe(phrase) ?: return false
            try {
                PcmSpeechSession(context, REPLY_SAMPLE_RATE).use { session ->
                    session.start()
                    val written = withTimeoutOrNull(5_000) {
                        withContext(Dispatchers.IO) { session.write(sample, 0, sample.size) }
                    } == true
                    session.endAudio()
                    val result = withTimeoutOrNull(5_000) { session.result.await() }
                    val matched = result?.text?.probeWords() == phrase.probeWords()
                    val verified = written && session.ready.isCompleted && result?.clean == true &&
                        result.endedAfterEof && matched && profile() == identity
                    if (verified) preferences.edit().putString(VERIFIED, identity).apply()
                    Log.i("DriveVoiceAudio", "Offline PCM challenge: verified=$verified, wrote=$written, " +
                        "ready=${session.ready.isCompleted}, clean=${result?.clean}, " +
                        "endedAfterEof=${result?.endedAfterEof}, matched=$matched")
                    return verified
                }
            } finally {
                sample.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w("DriveVoiceAudio", "Offline PCM challenge unavailable")
            return false
        } finally {
            try {
                runCatching { voice.close() }
            } finally {
                reservation.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    suspend fun listen(
        device: android.media.AudioDeviceInfo,
        routeReady: () -> Boolean,
        request: ReplyWindow,
        filterForRecognition: Boolean,
        automatic: Boolean,
        buttons: MediaButtonHandler?,
        onAmplitude: (Float?) -> Unit,
    ): VoiceInput {
        val pressed = AtomicBoolean()
        val canPress = AtomicBoolean()
        val silence = FilteredConfirmation()
        val raw = ShortArray(12 * REPLY_SAMPLE_RATE)
        val filtered = ShortArray(raw.size)
        val filter = if (filterForRecognition || automatic) {
            RecordingNoiseFilter.open(REPLY_SAMPLE_RATE, detection = automatic) ?: return VoiceInput.Unavailable
        } else null
        try {
            PcmSpeechSession(context, REPLY_SAMPLE_RATE).use { session ->
                session.start()
                buttons?.open { if (canPress.get()) pressed.set(true) }
                val ended = withTimeoutOrNull(11_700) {
                    capture.useMic(device, routeReady) { microphone ->
                        val openingAt = SystemClock.elapsedRealtime()
                        var readyAt: Long? = null
                        var count = 0
                        var sent = 0
                        var quiet = false
                        var reachedDeadline = false
                        while (count + REPLY_FRAME_SAMPLES <= raw.size) {
                            if (session.result.isCompleted || pressed.get()) break
                            val now = SystemClock.elapsedRealtime()
                            if (readyAt == null && session.ready.isCompleted) {
                                readyAt = now
                                canPress.set(true)
                            }
                            if (readyAt == null && now - openingAt >= 3_000) return@useMic null
                            val elapsed = readyAt?.let { now - it } ?: 0
                            val limit = if (automatic) 8_000L else request.timeoutMs
                            if (readyAt != null && elapsed >= limit) {
                                reachedDeadline = true
                                break
                            }
                            val budget = if (readyAt == null) 3_000 - (now - openingAt) else limit - elapsed
                            val frame = withTimeoutOrNull(minOf(250, budget)) { microphone.nextFrame() }
                            if (frame == null) {
                                if (readyAt == null || SystemClock.elapsedRealtime() < now + budget ||
                                    !microphone.valid()
                                ) return@useMic null
                                reachedDeadline = true
                                break
                            }
                            frame.copyInto(raw, count)
                            count += frame.size
                            filter?.processAvailable(raw, count, filtered) {
                                if (automatic && readyAt != null) silence.sample(it)
                            }
                            if (session.textObserved.get()) silence.observedText()
                            val available = if (filterForRecognition) checkNotNull(filter).availableSamples else count
                            val sending = if (filterForRecognition) filtered else raw
                            val remaining = budget - (SystemClock.elapsedRealtime() - now)
                            if (withTimeoutOrNull(minOf(250, remaining)) { session.write(sending, sent, available) } != true) {
                                if (!session.result.isCompleted) return@useMic null
                                break
                            }
                            sent = available
                            if (readyAt != null) onAmplitude(audioCheckLevel(frame, frame.size))
                            quiet = automatic && silence.canCloseForSilence(elapsed)
                            if (quiet) break
                        }
                        if (!microphone.valid() || readyAt == null) return@useMic null
                        WindowEnd(quiet, reachedDeadline, count, sent)
                    }
                }
                buttons?.endWindow()
                if (ended == null || !routeReady()) return VoiceInput.Unavailable
                if (pressed.get()) return VoiceInput.Button
                if (!session.result.isCompleted && filterForRecognition) {
                    val flushed = withTimeoutOrNull(200) {
                        withContext(Dispatchers.IO) {
                            checkNotNull(filter).finish(raw, ended.captured, filtered)
                            session.write(filtered, ended.sent, ended.captured)
                        }
                    }
                    if (flushed != true) return VoiceInput.Unavailable
                }
                session.endAudio()
                val decoded = withTimeoutOrNull(700) { session.result.await() }
                if (!routeReady()) return VoiceInput.Unavailable
                val hasText = session.textObserved.get()
                val maySend = silence.maySend(ended.quiet, decoded, hasText)
                if (request == ReplyWindow.CONFIRMATION) {
                    Log.d("DriveVoiceAudio", "PCM confirmation: quiet=${ended.quiet}, " +
                        "startEvent=${session.beginningDetected.get()}, text=$hasText, " +
                        "clean=${decoded?.clean}, endedAfterEof=${decoded?.endedAfterEof}, auto=$maySend")
                }
                if (decoded?.clean == true && decoded.endedAfterEof) {
                    decoded.text?.let { return VoiceInput.Heard(it) }
                }
                if (maySend) return VoiceInput.Silence
                return if (ended.reachedDeadline) VoiceInput.Timeout else VoiceInput.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return VoiceInput.Unavailable
        } finally {
            buttons?.endWindow()
            onAmplitude(null)
            raw.fill(0)
            filtered.fill(0)
            filter?.close()
        }
    }

    private data class WindowEnd(
        val quiet: Boolean,
        val reachedDeadline: Boolean,
        val captured: Int,
        val sent: Int,
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun profile(): String? {
        val manager = context.packageManager
        val providers = manager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull {
            val service = it.serviceInfo ?: return@mapNotNull null
            val info = manager.getPackageInfo(service.packageName, 0)
            "${service.packageName}/${service.name}:${info.longVersionCode}:${info.lastUpdateTime}"
        }.sorted().distinct()
        if (providers.isEmpty()) return null
        return "pcm-vi-1|${Build.FINGERPRINT}|${providers.joinToString("|")}"
    }

    private fun String.probeWords(): String =
        Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace('đ', 'd')
            .replace(Regex("[^a-z ]"), "").trim().replace(Regex("\\s+"), " ")

    private companion object { const val VERIFIED = "profile" }
}
