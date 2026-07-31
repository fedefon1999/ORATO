package com.orato.app.audio

/**
 * Recorder / analysis lifecycle exposed through the practice ViewModel.
 * Shutdown is idempotent; state never silently reports zeros after an error.
 */
enum class AudioRecordingState {
    Idle,
    Initializing,
    Recording,
    Stopping,
    Completed,
    Error,
}

/**
 * Classification of captured microphone input for the local report.
 * Does not infer emotion, confidence, stress, pitch, or speaking rate.
 */
enum class AudioInputQuality {
    GOOD,
    TOO_QUIET,
    CLIPPING,
    INSUFFICIENT_AUDIO,
    RECORDING_ERROR,
}

/**
 * Live development debug snapshot for the practice audio panel.
 * Does not expose the local file path.
 */
data class LiveAudioDebug(
    val state: AudioRecordingState = AudioRecordingState.Idle,
    val sampleRateHz: Int? = null,
    val audioSourceLabel: String? = null,
    val currentDbfs: Double? = null,
    val noiseFloorDbfs: Double? = null,
    val isSpeech: Boolean = false,
    val capturedDurationMs: Long = 0L,
    val droppedReadCount: Int = 0,
    val errorMessage: String? = null,
    // --- temporary VAD / segmentation debug ---
    val rawFrameDbfs: Double? = null,
    val speechOnThresholdDbfs: Double? = null,
    val speechOffThresholdDbfs: Double? = null,
    val vadState: VadState = VadState.Calibrating,
    val currentSpeechSegmentMs: Long = 0L,
    val currentSilenceSegmentMs: Long = 0L,
    val finalizedSpeechSegments: Int = 0,
    val finalizedInternalPauses: Int = 0,
)

/**
 * Immutable end-of-session audio metrics.
 * Raw measurements stay separate from any future user-facing interpretation.
 */
data class AudioSessionMetrics(
    val state: AudioRecordingState,
    val inputQuality: AudioInputQuality,
    /** Actual successfully captured duration in milliseconds. */
    val capturedDurationMs: Long,
    val droppedReadCount: Int,
    val sampleRateHz: Int?,
    val audioSourceLabel: String?,
    /**
     * Speech time / captured time as a percentage (0–100), or null if insufficient.
     * Speech time comes from finalized speech segments — never from total capture.
     */
    val speechRatioPercent: Double?,
    /**
     * Mean dBFS over frames classified as speech, or null if no valid speech.
     * Remains a signed dBFS value (typically negative); never converted to positive.
     */
    val meanSpeechDbfs: Double?,
    /**
     * Standard deviation of speech-frame dBFS (volume variation).
     * Kept as a raw dispersion measure — not a user “quality” score.
     */
    val volumeVariationStdDevDb: Double?,
    /** Clipped sample percentage over all captured PCM samples (0–100). */
    val clippingPercent: Double?,
    /** Approximate internal pauses between speech segments (leading/trailing excluded). */
    val approximatePauseCount: Int?,
    val medianPauseDurationMs: Long?,
    val longestPauseDurationMs: Long?,
    val pausesOver1500Ms: Int?,
    val errorMessage: String?,
    /**
     * True when valid audio is insufficient for presenting metrics.
     * UI should show “Dati audio insufficienti” instead of fabricated values.
     */
    val insufficientData: Boolean,
) {
    companion object {
        fun idle(): AudioSessionMetrics =
            AudioSessionMetrics(
                state = AudioRecordingState.Idle,
                inputQuality = AudioInputQuality.INSUFFICIENT_AUDIO,
                capturedDurationMs = 0L,
                droppedReadCount = 0,
                sampleRateHz = null,
                audioSourceLabel = null,
                speechRatioPercent = null,
                meanSpeechDbfs = null,
                volumeVariationStdDevDb = null,
                clippingPercent = null,
                approximatePauseCount = null,
                medianPauseDurationMs = null,
                longestPauseDurationMs = null,
                pausesOver1500Ms = null,
                errorMessage = null,
                insufficientData = true,
            )

        fun recordingError(message: String): AudioSessionMetrics =
            AudioSessionMetrics(
                state = AudioRecordingState.Error,
                inputQuality = AudioInputQuality.RECORDING_ERROR,
                capturedDurationMs = 0L,
                droppedReadCount = 0,
                sampleRateHz = null,
                audioSourceLabel = null,
                speechRatioPercent = null,
                meanSpeechDbfs = null,
                volumeVariationStdDevDb = null,
                clippingPercent = null,
                approximatePauseCount = null,
                medianPauseDurationMs = null,
                longestPauseDurationMs = null,
                pausesOver1500Ms = null,
                errorMessage = message,
                insufficientData = true,
            )
    }
}

/**
 * Combined local practice report handed across navigation.
 */
data class SessionPracticeReport(
    val body: com.orato.app.metrics.SessionBodyReport,
    val audio: AudioSessionMetrics,
)
