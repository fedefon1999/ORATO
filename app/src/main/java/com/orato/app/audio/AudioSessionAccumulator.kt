package com.orato.app.audio

/**
 * Accumulates per-frame analysis into immutable [AudioSessionMetrics].
 *
 * Pause reporting rules:
 * - Leading silence before the first speech segment is ignored.
 * - Trailing silence after the final speech segment is ignored.
 * - Only internal silence gaps between speech segments count as approximate pauses.
 * - Gaps shorter than [AudioMetricsConfig.MIN_SILENCE_SEGMENT_MS] are not counted.
 * - Speech runs shorter than [AudioMetricsConfig.MIN_SPEECH_SEGMENT_MS] are dropped
 *   before pause / speech-ratio aggregation.
 *
 * Labels pauses as approximate because no transcript exists yet.
 */
class AudioSessionAccumulator(
    private val sampleRateHz: Int,
    private val frameSampleCount: Int,
    private val config: AudioMetricsConfig = AudioMetricsConfig,
) {
    private val frameDurationMs: Double =
        frameSampleCount * 1_000.0 / sampleRateHz.toDouble()

    private var totalSamples: Long = 0L
    private var clippedSamples: Long = 0L
    private var droppedReads: Int = 0
    private var speechFrameCount: Int = 0
    private var totalFrameCount: Int = 0

    private val speechDbfsLevels = mutableListOf<Double>()

    // Raw frame-level speech flags for segment post-processing.
    private val speechFlags = mutableListOf<Boolean>()

    private var lastDbfs: Double? = null
    private var lastNoiseFloor: Double? = null
    private var lastIsSpeech: Boolean = false

    fun reset() {
        totalSamples = 0L
        clippedSamples = 0L
        droppedReads = 0
        speechFrameCount = 0
        totalFrameCount = 0
        speechDbfsLevels.clear()
        speechFlags.clear()
        lastDbfs = null
        lastNoiseFloor = null
        lastIsSpeech = false
    }

    fun recordDroppedRead() {
        droppedReads++
    }

    fun droppedReadCount(): Int = droppedReads

    fun capturedDurationMs(): Long = PcmMath.durationMs(totalSamples, sampleRateHz)

    fun liveSnapshot(
        state: AudioRecordingState,
        audioSourceLabel: String?,
        errorMessage: String? = null,
    ): LiveAudioDebug =
        LiveAudioDebug(
            state = state,
            sampleRateHz = sampleRateHz,
            audioSourceLabel = audioSourceLabel,
            currentDbfs = lastDbfs,
            noiseFloorDbfs = lastNoiseFloor,
            isSpeech = lastIsSpeech,
            capturedDurationMs = capturedDurationMs(),
            droppedReadCount = droppedReads,
            errorMessage = errorMessage,
        )

    fun acceptFrame(result: AudioFrameResult, noiseFloorDbfs: Double) {
        totalSamples += result.sampleCount
        clippedSamples += result.clippedSampleCount
        totalFrameCount++
        speechFlags.add(result.isSpeech)
        lastDbfs = result.dbfs
        lastNoiseFloor = noiseFloorDbfs
        lastIsSpeech = result.isSpeech
        if (result.isSpeech) {
            speechFrameCount++
            speechDbfsLevels.add(result.dbfs)
        }
    }

    /**
     * Builds the immutable session metrics.
     * @param keepMetrics when false (cancelled / failed), returns insufficient / error
     */
    fun buildMetrics(
        state: AudioRecordingState,
        audioSourceLabel: String?,
        errorMessage: String?,
    ): AudioSessionMetrics {
        if (state == AudioRecordingState.Error || errorMessage != null && state != AudioRecordingState.Completed) {
            return AudioSessionMetrics(
                state = AudioRecordingState.Error,
                inputQuality = AudioInputQuality.RECORDING_ERROR,
                capturedDurationMs = capturedDurationMs(),
                droppedReadCount = droppedReads,
                sampleRateHz = sampleRateHz,
                audioSourceLabel = audioSourceLabel,
                speechRatioPercent = null,
                meanSpeechDbfs = null,
                volumeVariationStdDevDb = null,
                clippingPercent = null,
                approximatePauseCount = null,
                medianPauseDurationMs = null,
                longestPauseDurationMs = null,
                pausesOver1500Ms = null,
                errorMessage = errorMessage ?: "Recording error",
                insufficientData = true,
            )
        }

        val durationMs = capturedDurationMs()
        val clippingPct = if (totalSamples > 0L) {
            clippedSamples * 100.0 / totalSamples.toDouble()
        } else {
            0.0
        }

        val segments = buildQualifiedSpeechSegments()
        val speechMs = segments.sumOf { (start, end) ->
            ((end - start) * frameDurationMs).toLong()
        }
        val speechRatio = if (durationMs > 0L) {
            speechMs.toDouble() / durationMs.toDouble()
        } else {
            0.0
        }
        val pauses = computeInternalPauses(segments)

        val meanSpeech = if (speechDbfsLevels.isNotEmpty()) {
            // Recompute mean only over frames that fall inside qualified segments
            // is complex; use speech-frame levels collected during VAD latch.
            // After min-duration filtering we still report mean of latched speech frames
            // when qualified speech exists; otherwise null.
            if (segments.isEmpty()) null else speechDbfsLevels.average()
        } else {
            null
        }

        val variation = if (segments.isEmpty()) null else PcmMath.standardDeviation(speechDbfsLevels)

        val quality = classifyQuality(
            durationMs = durationMs,
            speechRatio = speechRatio,
            meanSpeechDbfs = meanSpeech,
            clippingPercent = clippingPct,
            hasError = false,
        )

        // TOO_QUIET / CLIPPING still expose metrics; only insufficient / error hide them.
        val insufficient = quality == AudioInputQuality.INSUFFICIENT_AUDIO

        if (insufficient) {
            return AudioSessionMetrics(
                state = state,
                inputQuality = AudioInputQuality.INSUFFICIENT_AUDIO,
                capturedDurationMs = durationMs,
                droppedReadCount = droppedReads,
                sampleRateHz = sampleRateHz,
                audioSourceLabel = audioSourceLabel,
                speechRatioPercent = null,
                meanSpeechDbfs = null,
                volumeVariationStdDevDb = null,
                clippingPercent = clippingPct.takeIf { totalSamples > 0L },
                approximatePauseCount = null,
                medianPauseDurationMs = null,
                longestPauseDurationMs = null,
                pausesOver1500Ms = null,
                errorMessage = errorMessage,
                insufficientData = true,
            )
        }

        return AudioSessionMetrics(
            state = state,
            inputQuality = quality,
            capturedDurationMs = durationMs,
            droppedReadCount = droppedReads,
            sampleRateHz = sampleRateHz,
            audioSourceLabel = audioSourceLabel,
            speechRatioPercent = speechRatio * 100.0,
            meanSpeechDbfs = meanSpeech,
            volumeVariationStdDevDb = variation,
            clippingPercent = clippingPct,
            approximatePauseCount = pauses.count,
            medianPauseDurationMs = pauses.medianMs,
            longestPauseDurationMs = pauses.longestMs,
            pausesOver1500Ms = pauses.over1500,
            errorMessage = errorMessage,
            insufficientData = false,
        )
    }

    private data class PauseStats(
        val count: Int,
        val medianMs: Long?,
        val longestMs: Long?,
        val over1500: Int,
    )

    /**
     * Returns qualified speech segments as inclusive-exclusive frame index pairs
     * [start, end), after dropping runs shorter than MIN_SPEECH_SEGMENT_MS.
     */
    private fun buildQualifiedSpeechSegments(): List<Pair<Int, Int>> {
        val raw = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < speechFlags.size) {
            if (!speechFlags[i]) {
                i++
                continue
            }
            val start = i
            while (i < speechFlags.size && speechFlags[i]) i++
            val end = i
            val durationMs = ((end - start) * frameDurationMs).toLong()
            if (durationMs >= config.MIN_SPEECH_SEGMENT_MS) {
                raw.add(start to end)
            }
        }
        return raw
    }

    private fun computeInternalPauses(segments: List<Pair<Int, Int>>): PauseStats {
        if (segments.size < 2) {
            return PauseStats(count = 0, medianMs = null, longestMs = null, over1500 = 0)
        }
        val durations = mutableListOf<Long>()
        for (idx in 0 until segments.size - 1) {
            val gapFrames = segments[idx + 1].first - segments[idx].second
            val gapMs = (gapFrames * frameDurationMs).toLong()
            if (gapMs >= config.MIN_SILENCE_SEGMENT_MS) {
                durations.add(gapMs)
            }
        }
        if (durations.isEmpty()) {
            return PauseStats(count = 0, medianMs = null, longestMs = null, over1500 = 0)
        }
        return PauseStats(
            count = durations.size,
            medianMs = PcmMath.medianLong(durations),
            longestMs = durations.maxOrNull(),
            over1500 = durations.count { it > config.LONG_PAUSE_THRESHOLD_MS },
        )
    }

    private fun classifyQuality(
        durationMs: Long,
        speechRatio: Double,
        meanSpeechDbfs: Double?,
        clippingPercent: Double,
        hasError: Boolean,
    ): AudioInputQuality {
        if (hasError) return AudioInputQuality.RECORDING_ERROR
        if (durationMs < config.MIN_CAPTURED_DURATION_MS) {
            return AudioInputQuality.INSUFFICIENT_AUDIO
        }
        if (speechRatio < config.MIN_SPEECH_RATIO || meanSpeechDbfs == null) {
            return AudioInputQuality.INSUFFICIENT_AUDIO
        }
        if (clippingPercent >= config.CLIPPING_PERCENT_THRESHOLD) {
            return AudioInputQuality.CLIPPING
        }
        if (meanSpeechDbfs <= config.TOO_QUIET_MEAN_DBFS) {
            return AudioInputQuality.TOO_QUIET
        }
        return AudioInputQuality.GOOD
    }
}
