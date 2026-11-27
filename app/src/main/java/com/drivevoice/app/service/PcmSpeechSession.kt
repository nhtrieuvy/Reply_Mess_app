package com.drivevoice.app.service

import android.content.Context
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

internal data class PcmRecognitionResult(
    val text: String? = null,
    val endedAfterEof: Boolean = false,
    val clean: Boolean = false,
)

/** Only the app owns capture. The recognizer is fed PCM through a bounded, nonblocking pipe. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PcmSpeechSession(context: Context, private val rate: Int) : Closeable {
    private val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    private val descriptors = ParcelFileDescriptor.createPipe()
    private val input = descriptors[0]
    private val output = descriptors[1]
    private val eof = AtomicBoolean()
    private val storage = ByteArray(2_048)
    private val segments = mutableListOf<String>()
    val ready = CompletableDeferred<Unit>()
    val result = CompletableDeferred<PcmRecognitionResult>()
    val textObserved = AtomicBoolean()
    val beginningDetected = AtomicBoolean()

    fun start() {
        Os.fcntlInt(output.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { ready.complete(Unit) }
            override fun onBeginningOfSpeech() { beginningDetected.set(true) }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (partialResults.recognizedText() != null) textObserved.set(true)
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                segmentResults.recognizedText()?.let {
                    textObserved.set(true)
                    segments += it
                }
            }
            override fun onEndOfSegmentedSession() = complete()
            override fun onResults(results: Bundle?) = complete(results.recognizedText())
            override fun onError(error: Int) {
                Log.d("DriveVoiceAudio", "PCM recognizer ended: error=$error, ready=${ready.isCompleted}, eof=${eof.get()}")
                val clean = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (clean && eof.get()) complete()
                else result.complete(PcmRecognitionResult(endedAfterEof = eof.get(), clean = false))
            }
        })
        recognizer.startListening(SpeechRecognitionManager.recognitionIntent()
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, input)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE))
    }

    suspend fun write(samples: ShortArray, start: Int, end: Int): Boolean {
        var index = start
        while (index < end) {
            if (result.isCompleted) return false
            val count = minOf(storage.size / 2, end - index)
            repeat(count) {
                val value = samples[index + it].toInt()
                storage[it * 2] = value.toByte()
                storage[it * 2 + 1] = (value shr 8).toByte()
            }
            if (!writePipe(output, storage, count * 2)) return false
            index += count
        }
        return true
    }

    fun endAudio() {
        if (eof.compareAndSet(false, true)) runCatching { output.close() }
    }

    override fun close() {
        try {
            recognizer.destroy()
        } finally {
            endAudio()
            runCatching { input.close() }
            storage.fill(0)
        }
    }

    private fun complete(finalText: String? = null) {
        if (finalText != null) textObserved.set(true)
        val segmentedText = segments.joinToString(" ").takeIf { it.isNotBlank() }
        val consistent = finalText == null || segmentedText == null ||
            finalText.equals(segmentedText, ignoreCase = true)
        result.complete(PcmRecognitionResult(
            text = finalText ?: segmentedText,
            endedAfterEof = eof.get(),
            clean = consistent,
        ))
    }

    private fun Bundle?.recognizedText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

internal suspend fun writePipe(pipe: ParcelFileDescriptor, bytes: ByteArray, count: Int): Boolean {
    var written = 0
    while (written < count) {
        val length = try {
            Os.write(pipe.fileDescriptor, bytes, written, count - written)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EAGAIN && failure.errno != OsConstants.EINTR) return false
            0
        }
        if (length > 0) written += length else delay(5)
    }
    return true
}
