package com.orato.app.audio

/**
 * Latched VAD state after attack/release hysteresis.
 * Attack/release counters are internal; only finalized states are exposed.
 */
enum class VadState {
    Calibrating,
    Silence,
    Speech,
}

/**
 * Per-frame VAD output used by the session accumulator and debug panel.
 */
data class VadFrameResult(
    val isSpeech: Boolean,
    val vadState: VadState,
    val noiseFloorDbfs: Double,
    val speechOnThresholdDbfs: Double,
    val speechOffThresholdDbfs: Double,
    /** Duration of the open speech segment so far (0 when not in speech). */
    val currentSpeechSegmentMs: Long,
    /** Duration of the open silence segment so far (0 when in speech). */
    val currentSilenceSegmentMs: Long,
    /** True when this frame finalized a transition into speech. */
    val enteredSpeech: Boolean,
    /** True when this frame finalized a transition into silence. */
    val enteredSilence: Boolean,
)

/**
 * Lightweight deterministic voice-activity detector.
 *
 * Pipeline:
 * 1. Calibrate noise floor for [AudioMetricsConfig.NOISE_FLOOR_CALIBRATION_MS].
 * 2. Adaptive noise-floor estimation — speech never raises the floor.
 * 3. Separate speech-on / speech-off thresholds (hysteresis margins).
 * 4. Attack / release frame counts finalize Silence ↔ Speech transitions.
 * 5. Peak-drop release unlocks a stuck speech state after quiet calibration.
 *
 * Not a cloud model. All thresholds live in [AudioMetricsConfig].
 */
class VoiceActivityDetector(
    private val sampleRateHz: Int,
    private val nominalFrameSampleCount: Int,
    private val config: AudioMetricsConfig = AudioMetricsConfig,
) {
    private var calibratedMs: Double = 0.0
    private var noiseFloorDbfs: Double = config.SILENCE_DBFS
    private var calibrationSum: Double = 0.0
    private var calibrationFrames: Int = 0
    private var calibrating: Boolean = true

    private var inSpeech: Boolean = false
    private var attackCount: Int = 0
    private var releaseCount: Int = 0

    private var speechPeakDbfs: Double = config.SILENCE_DBFS
    private var currentSpeechSegmentMs: Double = 0.0
    private var currentSilenceSegmentMs: Double = 0.0

    fun noiseFloorDbfs(): Double = noiseFloorDbfs

    fun speechOnThresholdDbfs(): Double = noiseFloorDbfs + config.SPEECH_ON_MARGIN_DB

    fun speechOffThresholdDbfs(): Double = noiseFloorDbfs + config.SPEECH_OFF_MARGIN_DB

    fun isSpeech(): Boolean = inSpeech && !calibrating

    fun vadState(): VadState = when {
        calibrating -> VadState.Calibrating
        inSpeech -> VadState.Speech
        else -> VadState.Silence
    }

    fun currentSpeechSegmentMs(): Long = currentSpeechSegmentMs.toLong()

    fun currentSilenceSegmentMs(): Long = currentSilenceSegmentMs.toLong()

    fun reset() {
        calibratedMs = 0.0
        noiseFloorDbfs = config.SILENCE_DBFS
        calibrationSum = 0.0
        calibrationFrames = 0
        calibrating = true
        inSpeech = false
        attackCount = 0
        releaseCount = 0
        speechPeakDbfs = config.SILENCE_DBFS
        currentSpeechSegmentMs = 0.0
        currentSilenceSegmentMs = 0.0
    }

    /**
     * Feeds one frame.
     *
     * @param dbfs frame level in dBFS
     * @param frameDurationMs actual duration of this frame derived from the
     *        samples read, sample rate and channel count
     */
    fun processFrame(dbfs: Double, frameDurationMs: Double): VadFrameResult {
        val level = if (dbfs.isFinite()) dbfs else config.SILENCE_DBFS
        val duration = frameDurationMs.coerceAtLeast(0.0)
        var enteredSpeech = false
        var enteredSilence = false

        if (calibrating) {
            calibrationSum += level
            calibrationFrames++
            calibratedMs += duration
            currentSilenceSegmentMs += duration
            currentSpeechSegmentMs = 0.0
            if (calibratedMs >= config.NOISE_FLOOR_CALIBRATION_MS && calibrationFrames > 0) {
                noiseFloorDbfs = calibrationSum / calibrationFrames
                calibrating = false
            } else if (calibrationFrames > 0) {
                noiseFloorDbfs = calibrationSum / calibrationFrames
            }
            return snapshot(
                isSpeech = false,
                enteredSpeech = false,
                enteredSilence = false,
            )
        }

        val onThreshold = speechOnThresholdDbfs()
        val offThreshold = speechOffThresholdDbfs()

        if (inSpeech) {
            speechPeakDbfs = maxOf(speechPeakDbfs * config.SPEECH_PEAK_DECAY, level)
            val belowOff = level < offThreshold
            val droppedFromPeak = level <= speechPeakDbfs - config.SPEECH_PEAK_DROP_DB
            if (belowOff || droppedFromPeak) {
                releaseCount++
                if (releaseCount >= config.SPEECH_RELEASE_FRAMES) {
                    inSpeech = false
                    releaseCount = 0
                    attackCount = 0
                    enteredSilence = true
                    // Close speech segment; start silence segment with this frame.
                    currentSpeechSegmentMs = 0.0
                    currentSilenceSegmentMs = duration
                    adaptNoiseFloor(level)
                } else {
                    currentSpeechSegmentMs += duration
                    currentSilenceSegmentMs = 0.0
                }
            } else {
                releaseCount = 0
                currentSpeechSegmentMs += duration
                currentSilenceSegmentMs = 0.0
            }
        } else {
            if (level >= onThreshold) {
                attackCount++
                if (attackCount >= config.SPEECH_ATTACK_FRAMES) {
                    inSpeech = true
                    attackCount = 0
                    releaseCount = 0
                    enteredSpeech = true
                    speechPeakDbfs = level
                    // Close silence; start speech with this frame.
                    currentSilenceSegmentMs = 0.0
                    currentSpeechSegmentMs = duration
                } else {
                    currentSilenceSegmentMs += duration
                    currentSpeechSegmentMs = 0.0
                }
                // Do not raise the noise floor toward speech-level attack candidates.
            } else {
                attackCount = 0
                currentSilenceSegmentMs += duration
                currentSpeechSegmentMs = 0.0
                adaptNoiseFloor(level)
            }
        }

        return snapshot(
            isSpeech = inSpeech,
            enteredSpeech = enteredSpeech,
            enteredSilence = enteredSilence,
        )
    }

    /**
     * Legacy helper used by older unit tests — assumes the nominal frame duration.
     */
    fun processFrameDbfs(dbfs: Double): Boolean {
        val nominalMs = nominalFrameSampleCount * 1_000.0 /
            sampleRateHz.toDouble().coerceAtLeast(1.0)
        return processFrame(dbfs, nominalMs).isSpeech
    }

    private fun adaptNoiseFloor(level: Double) {
        // Active speech must never raise the floor (caller only invokes in silence /
        // release-to-silence). Asymmetric EMA tracks ambient without locking to
        // digital absolute silence forever.
        if (level < noiseFloorDbfs) {
            val alpha = config.NOISE_FLOOR_ADAPT_ALPHA_DOWN
            noiseFloorDbfs = (1.0 - alpha) * noiseFloorDbfs + alpha * level
        } else {
            val alpha = config.NOISE_FLOOR_ADAPT_ALPHA_UP
            noiseFloorDbfs = (1.0 - alpha) * noiseFloorDbfs + alpha * level
        }
    }

    private fun snapshot(
        isSpeech: Boolean,
        enteredSpeech: Boolean,
        enteredSilence: Boolean,
    ): VadFrameResult =
        VadFrameResult(
            isSpeech = isSpeech,
            vadState = vadState(),
            noiseFloorDbfs = noiseFloorDbfs,
            speechOnThresholdDbfs = speechOnThresholdDbfs(),
            speechOffThresholdDbfs = speechOffThresholdDbfs(),
            currentSpeechSegmentMs = currentSpeechSegmentMs.toLong(),
            currentSilenceSegmentMs = currentSilenceSegmentMs.toLong(),
            enteredSpeech = enteredSpeech,
            enteredSilence = enteredSilence,
        )
}
