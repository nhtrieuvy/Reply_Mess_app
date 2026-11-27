package com.drivevoice.app.service

internal data class FrameEvidence(
    val speechProbability: Float,
    val masked: Boolean,
    val clipped: Boolean = false,
)
internal data class SentenceBounds(val startMs: Int, val endMs: Int)
internal enum class SentenceState { LISTENING, COMPLETE, UNCLEAR }

/** The input is one 10 ms frame. Masked frames do not count as trustworthy quiet. */
internal class SentenceEndpointer {
    private var elapsedMs = 0
    private var speechRunMs = 0
    private var quietMs = 0
    private var startMs: Int? = null
    private var lastSpeechMs = 0
    private var lastMaskedMs = 0
    var bounds: SentenceBounds? = null
        private set
    private var status = SentenceState.LISTENING

    fun add(frame: FrameEvidence): SentenceState {
        if (status != SentenceState.LISTENING) return status
        elapsedMs += FRAME_MS
        when {
            frame.masked -> {
                speechRunMs = 0
                if (startMs != null) lastMaskedMs = elapsedMs
            }
            frame.speechProbability >= .65f -> {
                speechRunMs += FRAME_MS
                quietMs = 0
                lastSpeechMs = elapsedMs
                if (startMs == null && speechRunMs >= 120) {
                    startMs = (elapsedMs - speechRunMs - PADDING_MS).coerceAtLeast(0)
                }
            }
            frame.speechProbability <= .35f -> {
                speechRunMs = 0
                if (startMs != null) quietMs += FRAME_MS
            }
            else -> speechRunMs = 0
        }
        return when {
            startMs != null && !frame.masked && quietMs >= 1_200 ->
                complete(minOf(maxOf(lastSpeechMs, lastMaskedMs) + PADDING_MS, elapsedMs))
            (startMs == null && elapsedMs >= 3_000) || elapsedMs >= MAX_MS -> unclear()
            else -> status
        }
    }

    fun finishManually(): SentenceState {
        if (status != SentenceState.LISTENING) return status
        return if (startMs == null) unclear() else complete(elapsedMs)
    }

    private fun complete(endMs: Int): SentenceState {
        bounds = SentenceBounds(checkNotNull(startMs), endMs)
        return SentenceState.COMPLETE.also { status = it }
    }

    private fun unclear() = SentenceState.UNCLEAR.also { status = it }

    companion object {
        const val FRAME_MS = 10
        const val MAX_MS = 10_000
        private const val PADDING_MS = 250
    }
}
