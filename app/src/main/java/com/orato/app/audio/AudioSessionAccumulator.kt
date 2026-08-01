package com.orato.app.audio

/**
 * Kind of a finalized timeline segment.
 */
enum class AudioSegmentKind {
    Speech,
    Silence,
}

/**
 * Immutable finalized speech or silence segment on the session timeline.
 * Durations are summed from actual per-frame sample durations.
 */
data class AudioSegment(
    val kind: AudioSegmentKind,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Accumulates per-frame analysis into an explicit speech/silence timeline.
 *
 * Rules:
 * - Speech duration accumulates only from frames classified as speech.
 * - Silence duration accumulates only from non-speech frames.
 * - Total recording duration is never used as speech duration.
 * - Attack/release transitions finalize segments on the timeline.
 * - Internal pauses = silence segments between two valid speech segments.
 * - Leading and trailing silence are excluded from pause stats.
 * - Pauses ≥ [AudioMetricsConfig.MIN_SILENCE_SEGMENT_MS] are kept;
 *   pauses > [AudioMetricsConfig.LONG_PAUSE_THRESHOLD_MS] counted separately.
 * - [finalizeOpenSegment] must be called when recording stops.
 */
class AudioSessionAccumulator(
    private val sampleRateHz: Int,
    private val frameSampleCount: Int,
    private val config: AudioMetricsConfig = AudioMetricsConfig,
) {
    private var totalSamples: Long = 0L
    private var clippedSamples: Long = 0L
    private var droppedReads: Int = 0

    private var speechDurationMs: Double = 0.0
    private var silenceDurationMs: Double = 0.0

    private val speechDbfsLevels = mutableListOf<Double>()
    private val finalizedSegments = mutableListOf<AudioSegment>()

    private var openKind: AudioSegmentKind? = null
    private var openStartMs: Long = 0L
    private var openDurationMs: Double = 0.0
    private var timelineMs: Double = 0.0
    private var finalized: Boolean = false

    private var lastDbfs: Double? = null
    private var lastVad: VadFrameResult? = null

    fun reset() {
        totalSamples = 0L
        clippedSamples = 0L
        droppedReads = 0
        speechDurationMs = 0.0
        silenceDurationMs = 0.0
        speechDbfsLevels.clear()
        finalizedSegments.clear()
        openKind = null
        openStartMs = 0L
        openDurationMs = 0.0
        timelineMs = 0.0
        finalized = false
        lastDbfs = null
        lastVad = null
    }

    fun recordDroppedRead() {
        droppedReads++
    }

    fun droppedReadCount(): Int = droppedReads

    fun capturedDurationMs(): Long = PcmMath.durationMs(totalSamples, sampleRateHz)

    fun speechDurationMs(): Long = speechDurationMs.toLong()

    fun silenceDurationMs(): Long = silenceDurationMs.toLong()

    fun finalizedSegments(): List<AudioSegment> = finalizedSegments.toList()

    fun finalizedSpeechSegmentCount(): Int =
        qualifiedSpeechSegments().size

    fun finalizedInternalPauseCount(): Int =
        EffectiveSpeakingBlocks.build(qualifiedSpeechSegments())
            .significantPauseDurationsMs.size

    fun liveSnapshot(
        state: AudioRecordingState,
        audioSourceLabel: String?,
        errorMessage: String? = null,
    ): LiveAudioDebug {
        val vad = lastVad
        return LiveAudioDebug(
            state = state,
            sampleRateHz = sampleRateHz,
            audioSourceLabel = audioSourceLabel,
            currentDbfs = lastDbfs,
            noiseFloorDbfs = vad?.noiseFloorDbfs,
            isSpeech = vad?.isSpeech == true,
            capturedDurationMs = capturedDurationMs(),
            droppedReadCount = droppedReads,
            errorMessage = errorMessage,
            rawFrameDbfs = lastDbfs,
            speechOnThresholdDbfs = vad?.speechOnThresholdDbfs,
            speechOffThresholdDbfs = vad?.speechOffThresholdDbfs,
            vadState = vad?.vadState ?: VadState.Calibrating,
            currentSpeechSegmentMs = vad?.currentSpeechSegmentMs ?: 0L,
            currentSilenceSegmentMs = vad?.currentSilenceSegmentMs ?: 0L,
            finalizedSpeechSegments = finalizedSpeechSegmentCount(),
            finalizedInternalPauses = finalizedInternalPauseCount(),
        )
    }

    fun acceptFrame(result: AudioFrameResult) {
        check(!finalized) { "acceptFrame after finalizeOpenSegment" }
        totalSamples += result.sampleCount
        clippedSamples += result.clippedSampleCount
        lastDbfs = result.dbfs
        lastVad = result.vad

        val duration = result.durationMs.coerceAtLeast(0.0)
        if (duration <= 0.0) return

        if (result.isSpeech) {
            speechDurationMs += duration
            speechDbfsLevels.add(result.dbfs)
            appendToOpen(AudioSegmentKind.Speech, duration)
        } else {
            silenceDurationMs += duration
            appendToOpen(AudioSegmentKind.Silence, duration)
        }
    }

    /**
     * Closes any open speech/silence segment when recording stops.
     * Idempotent.
     */
    fun finalizeOpenSegment() {
        if (finalized) return
        closeOpenSegment()
        finalized = true
    }

    /**
     * Builds the immutable session metrics from the finalized segment timeline.
     */
    fun buildMetrics(
        state: AudioRecordingState,
        audioSourceLabel: String?,
        errorMessage: String?,
    ): AudioSessionMetrics {
        finalizeOpenSegment()

        if (state == AudioRecordingState.Error ||
            (errorMessage != null && state != AudioRecordingState.Completed)
        ) {
            return AudioSessionMetrics(
                state = AudioRecordingState.Error,
                inputQuality = AudioInputQuality.RECORDING_ERROR,
                capturedDurationMs = capturedDurationMs(),
                speechDurationMs = null,
                rawVoicedDurationMs = null,
                briefGapsMergedMs = null,
                longestSpeechSegmentMs = null,
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
                pauseBuckets = null,
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

        val speechSegments = qualifiedSpeechSegments()
        val timeline = EffectiveSpeakingBlocks.build(
            qualifiedSpeechSegments = speechSegments,
            mediumPauseThresholdMs = config.MEDIUM_PAUSE_THRESHOLD_MS,
        )
        val effectiveMs = timeline.effectiveSpeechDurationMs
        val rawVoiced = timeline.rawVoicedDurationMs
        val longestSpeech = timeline.longestContinuousSpeechMs
        // User-facing speaking ratio uses effective blocks — never raw capture alone.
        val speechRatio = if (durationMs > 0L) {
            effectiveMs.toDouble() / durationMs.toDouble()
        } else {
            0.0
        }
        val pauses = PauseBuckets.fromDurations(timeline.significantPauseDurationsMs)
        val pauseMedian = pauses.rawPauseDurationsMs.takeIf { it.isNotEmpty() }
            ?.let { PcmMath.medianLong(it) }
        val pauseLongest = pauses.rawPauseDurationsMs.maxOrNull()

        val meanSpeech = when {
            speechDbfsLevels.isEmpty() || speechSegments.isEmpty() -> null
            else -> {
                val avg = speechDbfsLevels.average()
                if (avg.isFinite()) avg else null
            }
        }
        val variation = when {
            speechSegments.isEmpty() -> null
            else -> PcmMath.standardDeviation(speechDbfsLevels)
        }

        val quality = classifyQuality(
            durationMs = durationMs,
            speechRatio = speechRatio,
            meanSpeechDbfs = meanSpeech,
            clippingPercent = clippingPct,
            hasError = false,
        )

        val insufficient = quality == AudioInputQuality.INSUFFICIENT_AUDIO ||
            meanSpeech == null ||
            speechSegments.isEmpty()

        if (insufficient) {
            return AudioSessionMetrics(
                state = state,
                inputQuality = AudioInputQuality.INSUFFICIENT_AUDIO,
                capturedDurationMs = durationMs,
                speechDurationMs = effectiveMs.takeIf { speechSegments.isNotEmpty() },
                rawVoicedDurationMs = rawVoiced.takeIf { speechSegments.isNotEmpty() },
                briefGapsMergedMs = timeline.briefGapsMergedMs.takeIf { speechSegments.isNotEmpty() },
                longestSpeechSegmentMs = longestSpeech.takeIf { speechSegments.isNotEmpty() },
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
                pauseBuckets = null,
                errorMessage = errorMessage,
                insufficientData = true,
            )
        }

        return AudioSessionMetrics(
            state = state,
            inputQuality = quality,
            capturedDurationMs = durationMs,
            speechDurationMs = effectiveMs,
            rawVoicedDurationMs = rawVoiced,
            briefGapsMergedMs = timeline.briefGapsMergedMs,
            longestSpeechSegmentMs = longestSpeech,
            droppedReadCount = droppedReads,
            sampleRateHz = sampleRateHz,
            audioSourceLabel = audioSourceLabel,
            speechRatioPercent = speechRatio * 100.0,
            meanSpeechDbfs = meanSpeech,
            volumeVariationStdDevDb = variation,
            clippingPercent = clippingPct,
            approximatePauseCount = pauses.rawCount,
            medianPauseDurationMs = pauseMedian,
            longestPauseDurationMs = pauseLongest,
            pausesOver1500Ms = pauses.longCount,
            pauseBuckets = pauses,
            errorMessage = errorMessage,
            insufficientData = false,
        )
    }

    private fun appendToOpen(kind: AudioSegmentKind, durationMs: Double) {
        if (openKind == null) {
            openKind = kind
            openStartMs = timelineMs.toLong()
            openDurationMs = durationMs
            timelineMs += durationMs
            return
        }
        if (openKind != kind) {
            closeOpenSegment()
            openKind = kind
            openStartMs = timelineMs.toLong()
            openDurationMs = durationMs
            timelineMs += durationMs
        } else {
            openDurationMs += durationMs
            timelineMs += durationMs
        }
    }

    private fun closeOpenSegment() {
        val kind = openKind ?: return
        val endMs = openStartMs + openDurationMs.toLong()
        if (openDurationMs > 0.0) {
            finalizedSegments.add(
                AudioSegment(
                    kind = kind,
                    startMs = openStartMs,
                    endMs = endMs,
                ),
            )
        }
        openKind = null
        openDurationMs = 0.0
    }

    /**
     * Valid speech segments after dropping runs shorter than MIN_SPEECH_SEGMENT_MS.
     * Adjacent speech segments separated only by sub-minimum silence are not merged
     * here — pause filtering handles short gaps separately.
     */
    private fun qualifiedSpeechSegments(): List<AudioSegment> =
        finalizedSegments.filter {
            it.kind == AudioSegmentKind.Speech &&
                it.durationMs >= config.MIN_SPEECH_SEGMENT_MS
        }

    private data class PauseStats(
        val buckets: PauseBuckets,
        val medianMs: Long?,
        val longestMs: Long?,
    )

    /**
     * Internal pauses = silence between consecutive qualified speech segments.
     * Leading silence (before first speech) and trailing silence (after last)
     * are excluded by construction. All raw durations ≥ min silence are kept
     * for debugging and then bucketed for the user-facing report.
     */
    private fun computeInternalPauses(speechSegments: List<AudioSegment>): PauseStats {
        if (speechSegments.size < 2) {
            return PauseStats(
                buckets = PauseBuckets.empty(),
                medianMs = null,
                longestMs = null,
            )
        }
        val durations = mutableListOf<Long>()
        for (idx in 0 until speechSegments.size - 1) {
            val gapMs = speechSegments[idx + 1].startMs - speechSegments[idx].endMs
            if (gapMs >= config.MIN_SILENCE_SEGMENT_MS) {
                durations.add(gapMs)
            }
        }
        val buckets = PauseBuckets.fromDurations(durations)
        if (buckets.rawPauseDurationsMs.isEmpty()) {
            return PauseStats(
                buckets = buckets,
                medianMs = null,
                longestMs = null,
            )
        }
        return PauseStats(
            buckets = buckets,
            medianMs = PcmMath.medianLong(buckets.rawPauseDurationsMs),
            longestMs = buckets.rawPauseDurationsMs.maxOrNull(),
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
