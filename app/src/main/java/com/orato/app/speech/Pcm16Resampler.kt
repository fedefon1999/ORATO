package com.orato.app.speech

/**
 * Incremental mono PCM16 resampler targeting [SpeechConfig.TARGET_SAMPLE_RATE_HZ].
 * Pure Kotlin — deterministic linear interpolation, no Android UI deps.
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
    private val step: Double = inputRateHz.toDouble() / outputRateHz.toDouble()
    private var cursor: Double = 0.0
    private val history = ArrayList<Short>(8)
    private var historyStartIndex: Long = 0L

    var inputSampleCount: Long = 0L
        private set
    var outputSampleCount: Long = 0L
        private set

    fun process(input: ShortArray, offset: Int = 0, length: Int = input.size): ShortArray {
        require(offset >= 0 && length >= 0 && offset + length <= input.size)
        if (length == 0) return ShortArray(0)

        if (passthrough) {
            inputSampleCount += length.toLong()
            outputSampleCount += length.toLong()
            return input.copyOfRange(offset, offset + length)
        }

        for (i in 0 until length) {
            history.add(input[offset + i])
        }
        inputSampleCount += length.toLong()

        val availableEnd = inputSampleCount
        val out = ArrayList<Short>(((length / step) + 2).toInt().coerceAtLeast(1))
        while (cursor + 1.0 < availableEnd.toDouble() + 1e-12) {
            out.add(interpolateAt(cursor))
            cursor += step
        }
        trimHistory()
        outputSampleCount += out.size.toLong()
        return out.toShortArray()
    }

    fun flush(): ShortArray {
        if (passthrough || history.isEmpty()) {
            history.clear()
            return ShortArray(0)
        }
        val lastIndex = inputSampleCount - 1L
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

    private fun trimHistory() {
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

/** Converts signed PCM16 to float samples in [-1.0, 1.0]. Clips outliers. */
object Pcm16ToFloatConverter {
    private const val SCALE = 32768.0f

    fun convert(samples: ShortArray, offset: Int = 0, length: Int = samples.size): FloatArray {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size)
        val out = FloatArray(length)
        for (i in 0 until length) {
            val v = samples[offset + i] / SCALE
            out[i] = when {
                v > 1.0f -> 1.0f
                v < -1.0f -> -1.0f
                else -> v
            }
        }
        return out
    }
}

/**
 * Downmixes interleaved PCM16 to mono by averaging channels.
 * For mono input, returns a copy of the range.
 */
object MonoDownmixer {
    fun toMono(samples: ShortArray, channelCount: Int, offset: Int = 0, length: Int = samples.size): ShortArray {
        require(channelCount >= 1)
        require(offset >= 0 && length >= 0 && offset + length <= samples.size)
        require(length % channelCount == 0) { "length must be a multiple of channelCount" }
        if (channelCount == 1) {
            return samples.copyOfRange(offset, offset + length)
        }
        val frames = length / channelCount
        val out = ShortArray(frames)
        var idx = offset
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channelCount) {
                sum += samples[idx++]
            }
            out[f] = (sum / channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
