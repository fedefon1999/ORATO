package com.orato.app.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Pure PCM helpers used by frame analysis and unit tests.
 * All functions are deterministic and NaN/Infinity-safe for silent input.
 */
object PcmMath {

    /**
     * Root-mean-square of 16-bit PCM samples.
     * Empty input → 0.
     */
    fun rms(samples: ShortArray, offset: Int = 0, length: Int = samples.size): Double {
        if (length <= 0 || offset < 0 || offset + length > samples.size) return 0.0
        var sumSquares = 0.0
        val end = offset + length
        for (i in offset until end) {
            val v = samples[i].toDouble()
            sumSquares += v * v
        }
        return sqrt(sumSquares / length)
    }

    /**
     * Converts linear RMS amplitude to dBFS relative to [AudioMetricsConfig.PCM_FULL_SCALE].
     *
     * Guarantees:
     * - never returns NaN or Infinity;
     * - absolute silence maps to [AudioMetricsConfig.SILENCE_DBFS];
     * - values are clamped to a finite range.
     */
    fun rmsToDbfs(
        rms: Double,
        fullScale: Double = AudioMetricsConfig.PCM_FULL_SCALE,
        epsilon: Double = AudioMetricsConfig.DBFS_EPSILON,
        silenceDbfs: Double = AudioMetricsConfig.SILENCE_DBFS,
    ): Double {
        if (!rms.isFinite() || rms <= 0.0 || !fullScale.isFinite() || fullScale <= 0.0) {
            return silenceDbfs
        }
        val ratio = (rms / fullScale).coerceAtLeast(epsilon)
        val dbfs = 20.0 * log10(ratio)
        return when {
            !dbfs.isFinite() -> silenceDbfs
            dbfs < silenceDbfs -> silenceDbfs
            else -> dbfs
        }
    }

    /**
     * Percentage (0–100) of samples whose absolute value is at or above the
     * clipping threshold (fraction of full scale).
     */
    fun clippingPercent(
        samples: ShortArray,
        offset: Int = 0,
        length: Int = samples.size,
        thresholdRatio: Double = AudioMetricsConfig.CLIPPING_THRESHOLD_RATIO,
        fullScale: Double = AudioMetricsConfig.PCM_FULL_SCALE,
    ): Double {
        if (length <= 0 || offset < 0 || offset + length > samples.size) return 0.0
        val threshold = (thresholdRatio * fullScale).toInt().coerceAtLeast(1)
        var clipped = 0
        val end = offset + length
        for (i in offset until end) {
            val abs = if (samples[i] == Short.MIN_VALUE) {
                // |Short.MIN_VALUE| does not fit in a Short; treat as full-scale.
                fullScale.toInt()
            } else {
                kotlin.math.abs(samples[i].toInt())
            }
            if (abs >= threshold) clipped++
        }
        return clipped * 100.0 / length
    }

    /** Sample count for one analysis frame at [sampleRateHz]. */
    fun frameSampleCount(
        sampleRateHz: Int,
        frameDurationMs: Int = AudioMetricsConfig.FRAME_DURATION_MS,
    ): Int {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        return ((sampleRateHz.toLong() * frameDurationMs) / 1_000L).toInt().coerceAtLeast(1)
    }

    /** Duration in milliseconds for [sampleCount] mono PCM samples. */
    fun durationMs(sampleCount: Long, sampleRateHz: Int): Long {
        if (sampleRateHz <= 0 || sampleCount <= 0L) return 0L
        return (sampleCount * 1_000L) / sampleRateHz
    }

    /**
     * Exact frame duration in milliseconds from the samples actually captured.
     *
     * `durationMs = sampleCount * 1000 / (sampleRateHz * channelCount)`
     *
     * For mono interleaved PCM, [sampleCount] is the number of samples read
     * from [android.media.AudioRecord.read].
     */
    fun frameDurationMs(
        sampleCount: Int,
        sampleRateHz: Int,
        channelCount: Int = AudioMetricsConfig.CHANNEL_COUNT,
    ): Double {
        if (sampleCount <= 0 || sampleRateHz <= 0 || channelCount <= 0) return 0.0
        return sampleCount * 1_000.0 / (sampleRateHz.toDouble() * channelCount.toDouble())
    }

    /**
     * Population standard deviation of [values], or null when fewer than 2 values.
     * Returns 0.0 (never NaN) when variance underflows.
     */
    fun standardDeviation(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.sum() / values.size
        var sumSq = 0.0
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        val variance = sumSq / values.size
        val std = sqrt(variance)
        return if (std.isFinite()) std else 0.0
    }

    /** Median of a list of Longs, or null when empty. */
    fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2L
        } else {
            sorted[mid]
        }
    }
}
