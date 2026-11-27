package com.drivevoice.app.service

import androidx.annotation.Keep
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.exp

/** One owner feeds mono PCM in frame order and closes the model when the capture ends. */
internal class RecordingNoiseFilter private constructor(
    private val sampleRate: Int,
    private var handle: Long,
) : Closeable {
    private val frameSize = sampleRate / 100
    private val input = ShortArray(frameSize)
    private val output = ShortArray(frameSize)
    private val lowAlpha = 1f - exp(-2f * Math.PI.toFloat() * 120f / sampleRate)
    private var lowBand = 0f
    private var recentEnergy = 0f
    private var processed = 0
    val availableSamples: Int get() = (processed - FILTER_DELAY_FRAMES * frameSize).coerceAtLeast(0)
    var impaired = false
        private set

    fun processAvailable(
        raw: ShortArray,
        count: Int,
        filtered: ShortArray,
        onFrame: ((FrameEvidence) -> Unit)?,
    ) {
        while (processed + frameSize <= count) processFrame(raw, count, filtered, onFrame)
    }

    fun finish(raw: ShortArray, count: Int, filtered: ShortArray) {
        while (processed < count + FILTER_DELAY_FRAMES * frameSize) {
            processFrame(raw, count, filtered, onFrame = null)
        }
    }

    private fun processFrame(raw: ShortArray, count: Int, filtered: ShortArray, onFrame: ((FrameEvidence) -> Unit)?) {
        check(handle != 0L)
        input.fill(0)
        if (processed < count) raw.copyInto(input, startIndex = processed, endIndex = minOf(processed + frameSize, count))
        var energy = 0f
        var lowEnergy = 0f
        var saturated = 0
        for (sample in input) {
            val scaled = sample / 32768f
            lowBand += lowAlpha * (scaled - lowBand)
            energy += scaled * scaled
            lowEnergy += lowBand * lowBand
            if (abs(sample.toInt()) >= 32_000) saturated++
        }
        energy /= frameSize
        lowEnergy /= frameSize
        val wind = energy > 0.0004f && lowEnergy > 0.70f * energy
        val clipping = saturated >= frameSize / 50
        val suddenNoise = energy > 0.015f && energy > recentEnergy * 6f
        recentEnergy += 0.1f * (energy - recentEnergy)

        val speech = RnNoiseNative.process(handle, input, output, sampleRate, wind)
        check(speech.isFinite() && speech >= 0f)
        val masked = wind || clipping || (suddenNoise && speech < .65f)
        if (onFrame != null || processed < count) impaired = impaired || masked
        // The pinned model overlaps two frames of analysis/synthesis. Flush the tail before replay.
        val destination = processed - FILTER_DELAY_FRAMES * frameSize
        if (destination in 0 until count) {
            val valid = minOf(frameSize, count - destination)
            for (index in 0 until valid) {
                filtered[destination + index] = (
                    .90f * output[index] + .10f * raw[destination + index]
                ).toInt().toShort()
            }
        }
        processed += frameSize
        onFrame?.invoke(FrameEvidence(speech, masked, clipping))
    }

    override fun close() {
        if (handle != 0L) RnNoiseNative.destroy(handle)
        handle = 0L
        input.fill(0)
        output.fill(0)
    }

    companion object {
        private const val FILTER_DELAY_FRAMES = 2

        fun open(sampleRate: Int, detection: Boolean): RecordingNoiseFilter? {
            if (sampleRate != 48_000 && sampleRate != 16_000) return null
            val handle = try {
                RnNoiseNative.create(detection)
            } catch (_: LinkageError) {
                return null
            }
            return if (handle == 0L) null else RecordingNoiseFilter(sampleRate, handle)
        }
    }
}

@Keep
private object RnNoiseNative {
    init { System.loadLibrary("drivevoice_audio") }

    external fun create(detection: Boolean): Long
    external fun process(handle: Long, input: ShortArray, output: ShortArray, sampleRate: Int, wind: Boolean): Float
    external fun destroy(handle: Long)
}
