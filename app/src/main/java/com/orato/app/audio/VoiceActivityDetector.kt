package com.orato.app.audio

/**
 * Lightweight deterministic voice-activity detector.
 *
 * Pipeline:
 * 1. Calibrate noise floor for [AudioMetricsConfig.NOISE_FLOOR_CALIBRATION_MS].
 * 2. Adaptive noise-floor EMA on non-speech frames.
 * 3. Candidate = dBFS ≥ noiseFloor + margin.
 * 4. Attack / release hysteresis on consecutive frames.
 * 5. Segments shorter than [AudioMetricsConfig.MIN_SPEECH_SEGMENT_MS] are dropped
 *    when finalizing pause / speech-ratio aggregates (see [AudioSessionAccumulator]).
 *
 * Not a cloud model. All thresholds live in [AudioMetricsConfig].
 */
class VoiceActivityDetector(
    private val sampleRateHz: Int,
    private val frameSampleCount: Int,
    private val config: AudioMetricsConfig = AudioMetricsConfig,
) {
    private val frameDurationMs: Double =
        frameSampleCount * 1_000.0 / sampleRateHz.toDouble()

    private var calibratedMs: Double = 0.0
    private var noiseFloorDbfs: Double = config.SILENCE_DBFS
    private var calibrationSum: Double = 0.0
    private var calibrationFrames: Int = 0
    private var calibrating: Boolean = true

    private var inSpeech: Boolean = false
    private var attackCount: Int = 0
    private var releaseCount: Int = 0

    /** Current adaptive / calibrated noise floor in dBFS. */
    fun noiseFloorDbfs(): Double = noiseFloorDbfs

    /** Whether the detector currently latches speech after hysteresis. */
    fun isSpeech(): Boolean = inSpeech

    fun reset() {
        calibratedMs = 0.0
        noiseFloorDbfs = config.SILENCE_DBFS
        calibrationSum = 0.0
        calibrationFrames = 0
        calibrating = true
        inSpeech = false
        attackCount = 0
        releaseCount = 0
    }

    /**
     * Feeds one frame's dBFS level.
     * @return whether the frame is classified as speech after hysteresis
     *         (always false during the calibration window)
     */
    fun processFrameDbfs(dbfs: Double): Boolean {
        val level = if (dbfs.isFinite()) dbfs else config.SILENCE_DBFS

        if (calibrating) {
            calibrationSum += level
            calibrationFrames++
            calibratedMs += frameDurationMs
            if (calibratedMs >= config.NOISE_FLOOR_CALIBRATION_MS && calibrationFrames > 0) {
                noiseFloorDbfs = calibrationSum / calibrationFrames
                calibrating = false
            } else {
                // Provisional floor during calibration (mean so far).
                noiseFloorDbfs = calibrationSum / calibrationFrames
            }
            inSpeech = false
            attackCount = 0
            releaseCount = 0
            return false
        }

        val candidate = level >= noiseFloorDbfs + config.SPEECH_MARGIN_DB

        if (inSpeech) {
            if (candidate) {
                releaseCount = 0
            } else {
                releaseCount++
                if (releaseCount >= config.SPEECH_RELEASE_FRAMES) {
                    inSpeech = false
                    releaseCount = 0
                    attackCount = 0
                    // Adapt noise floor on confirmed silence frames.
                    adaptNoiseFloor(level)
                }
            }
        } else {
            if (candidate) {
                attackCount++
                if (attackCount >= config.SPEECH_ATTACK_FRAMES) {
                    inSpeech = true
                    attackCount = 0
                    releaseCount = 0
                }
            } else {
                attackCount = 0
                adaptNoiseFloor(level)
            }
        }

        return inSpeech
    }

    private fun adaptNoiseFloor(level: Double) {
        val alpha = config.NOISE_FLOOR_ADAPT_ALPHA
        noiseFloorDbfs = (1.0 - alpha) * noiseFloorDbfs + alpha * level
    }
}
