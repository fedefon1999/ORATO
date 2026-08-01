package com.orato.app.speech

/**
 * Incremental mono PCM16 resampler targeting [SpeechConfig.TARGET_SAMPLE_RATE_HZ].
 *
 * - No Android / UI dependencies
 * - Deterministic linear interpolation
 * - Does not mutate the caller's input buffer
 * - Processes audio incrementally with bounded leftover state
 * - [flush] emits remaining output samples at stop
 *
 * When [inputRateHz] equals the target rate, frames pass through (copied).
 */
class Pcm16Resampler(
    private val inputRateHz: Int,
    private val outputRateHz: Int = SpeechConfig.TARGET_SAMPLE_RATE_HZ,
) {
    init {
        require(inputRateHz > 0) { "inputRateHz must be > 0" }
        require(outputRateHz > 0) { "outputRateHz must be > 0" }
    }

    private val passthrough: Boolean = inputRateHz == outputRateHz

    /** Step in input-sample units per output sample. */
    private val step: Double = inputRateHz.toDouble() / outputRateHz.toDouble()

    /**
     * Continuous read cursor in the concatenated input stream (sample units).
     * Advances by [step] for each emitted output sample.
     */
    private var cursor: Double = 0.0

    /** Ring of recent input samples needed for interpolation across chunk boundaries. */
    private val history = ArrayList<Short>(8)

    /** Absolute index of history[0] in the input stream. */
    private var historyStartIndex: Long = 0L

    var inputSampleCount: Long = 0L
        private set
    var outputSampleCount: Long = 0L
        private set

    /**
     * Resamples [length] samples starting at [offset] in [input].
     * Returns a new ShortArray (possibly empty). Never mutates [input].
     */
    fun process(input: ShortArray, offset: Int = 0, length: Int = input.size): ShortArray {
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "Invalid range offset=$offset length=$length size=${input.size}"
        }
        if (length == 0) return ShortArray(0)

        if (passthrough) {
            inputSampleCount += length.toLong()
            outputSampleCount += length.toLong()
            return input.copyOfRange(offset, offset + length)
        }

        appendHistory(input, offset, length)
        inputSampleCount += length.toLong()

        val availableEnd = inputSampleCount // exclusive
        // Need sample at floor(cursor)+1 for interpolation → cursor+1 < availableEnd
        val out = ArrayList<Short>(((length / step) + 2).toInt().coerceAtLeast(1))
        while (cursor + 1.0 < availableEnd.toDouble() + 1e-12) {
            out.add(interpolateAt(cursor))
            cursor += step
        }
        trimHistory()
        outputSampleCount += out.size.toLong()
        return out.toShortArray()
    }

    /**
     * Flushes remaining output using zero-order hold on the last input sample
     * so total output duration matches input duration within a small tolerance.
     */
    fun flush(): ShortArray {
        if (passthrough || history.isEmpty()) {
            history.clear()
            return ShortArray(0)
        }

        val lastIndex = inputSampleCount - 1L
        // Emit until cursor reaches the end of the input timeline.
        val endCursor = lastIndex.toDouble()
        val out = ArrayList<Short>(4)
        while (cursor <= endCursor + 1e-9) {
            val idx = cursor.toLong().coerceIn(0L, lastIndex)
            out.add(sampleAt(idx))
            cursor += step
        }
        history.clear()
        outputSampleCount += out.size.toLong()
        return out.toShortArray()
    }

    fun reset() {
        cursor = 0.0
        history.clear()
        historyStartIndex = 0L
        inputSampleCount = 0L
        outputSampleCount = 0L
    }

    fun expectedOutputDurationMs(inputSamples: Long = inputSampleCount): Double =
        inputSamples * 1000.0 / inputRateHz.toDouble()

    fun actualOutputDurationMs(): Double =
        outputSampleCount * 1000.0 / outputRateHz.toDouble()

    private fun appendHistory(input: ShortArray, offset: Int, length: Int) {
        for (i in 0 until length) {
            history.add(input[offset + i])
        }
    }

    private fun trimHistory() {
        // Keep samples from floor(cursor) onward (need floor and floor+1).
        val keepFrom = cursor.toLong().coerceAtLeast(0L)
        val drop = (keepFrom - historyStartIndex).toInt()
        if (drop > 0 && drop < history.size) {
            repeat(drop) { history.removeAt(0) }
            historyStartIndex = keepFrom
        } else if (drop >= history.size && history.isNotEmpty()) {
            val last = history.last()
            history.clear()
            history.add(last)
            historyStartIndex = inputSampleCount - 1L
        }
    }

    private fun interpolateAt(position: Double): Short {
        val i0 = position.toLong()
        val frac = position - i0
        val s0 = sampleAt(i0)
        val s1 = sampleAt(i0 + 1L)
        if (frac < 1e-12) return s0
        val mixed = s0 + (s1 - s0) * frac
        return mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun sampleAt(absoluteIndex: Long): Short {
        val local = (absoluteIndex - historyStartIndex).toInt()
        return when {
            local < 0 -> history.first()
            local >= history.size -> history.last()
            else -> history[local]
        }
    }
}
