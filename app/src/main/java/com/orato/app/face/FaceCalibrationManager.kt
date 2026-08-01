package com.orato.app.face

import com.orato.app.pose.PoseLandmarkId
import com.orato.app.pose.UpperBodyPoseFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class FaceCalibrationUiState {
    POSITION_FACE,
    MOVE_CLOSER,
    MOVE_FARTHER,
    LOOK_AT_CAMERA,
    HOLD_STILL,
    SHOW_SHOULDERS,
    COMPLETED,
}

data class UpperBodyCalibrationEvidence(
    val timestampMs: Long,
    val shouldersValid: Boolean,
    val torsoValid: Boolean,
    val sessionId: Long,
)

data class FaceCalibrationProgress(
    val uiState: FaceCalibrationUiState,
    val acceptedSamples: Int,
    val requiredSamples: Int,
    val validContinuousDurationMs: Long,
    val requiredDurationMs: Long,
    val faceValid: Boolean,
    val irisValid: Boolean,
    val shouldersValid: Boolean?,
    val upperBodyEvidenceAgeMs: Long? = null,
    val profile: FaceCalibrationProfile? = null,
) {
    /**
     * Truthful progress from continuous valid duration (not wall-clock alone).
     */
    val progressFraction: Float
        get() {
            val durationFrac = if (requiredDurationMs <= 0L) {
                0f
            } else {
                validContinuousDurationMs.toFloat() / requiredDurationMs.toFloat()
            }
            val sampleFrac = if (requiredSamples <= 0) {
                0f
            } else {
                acceptedSamples.toFloat() / requiredSamples.toFloat()
            }
            return min(1f, min(durationFrac, sampleFrac))
        }
}

/**
 * Session-specific face calibration requiring a continuous valid window of at least
 * [FaceMetricsConfig.MIN_VALID_CALIBRATION_DURATION_MS].
 *
 * Does not persist biometric geometry.
 */
class FaceCalibrationManager(
    private val requireUpperTorso: Boolean = false,
    private val requiredSamples: Int = FaceMetricsConfig.CALIBRATION_MIN_ACCEPTED_SAMPLES,
    private val requiredDurationMs: Long = FaceMetricsConfig.MIN_VALID_CALIBRATION_DURATION_MS,
    private val sessionId: Long = 0L,
) {
    private val samples = mutableListOf<FaceFrame>()
    private var completedProfile: FaceCalibrationProfile? = null
    private var windowStartMs: Long = -1L
    private var lastAcceptedMs: Long = -1L
    private var lastAnyMs: Long = -1L
    private var lastUpperBody: UpperBodyCalibrationEvidence? = null

    fun reset() {
        samples.clear()
        completedProfile = null
        windowStartMs = -1L
        lastAcceptedMs = -1L
        lastAnyMs = -1L
        lastUpperBody = null
    }

    fun onPoseFrame(pose: UpperBodyPoseFrame?, poseSessionId: Long = sessionId) {
        if (!requireUpperTorso) {
            lastUpperBody = null
            return
        }
        if (pose == null) return
        if (poseSessionId != sessionId) return
        val shoulders = hasVisibleShoulders(pose)
        val torso = hasVisibleUpperTorso(pose)
        val ts = pose.timestampMs.takeIf { it > 0L } ?: return
        lastUpperBody = UpperBodyCalibrationEvidence(
            timestampMs = ts,
            shouldersValid = shoulders,
            torsoValid = torso,
            sessionId = poseSessionId,
        )
    }

    fun process(frame: FaceFrame): FaceCalibrationProgress {
        completedProfile?.let { profile ->
            return completedProgress(profile)
        }

        val now = frame.timestampMs
        if (lastAnyMs >= 0L && now < lastAnyMs) {
            return currentProgress(uiHint(frame, validateFace(frame), upperBodyOk(now)))
        }
        lastAnyMs = now

        val faceCheck = validateFace(frame)
        val bodyOk = upperBodyOk(now)
        val uiState = uiHint(frame, faceCheck, bodyOk)

        val accept = faceCheck == FaceValidityReason.OK &&
            bodyOk &&
            frame.leftIrisHorizontalRatio != null &&
            frame.rightIrisHorizontalRatio != null &&
            frame.headYawDeg != null &&
            frame.headPitchDeg != null &&
            frame.headRollDeg != null

        if (accept) {
            if (windowStartMs < 0L) {
                windowStartMs = now
                lastAcceptedMs = now
                samples.clear()
                samples += frame
            } else {
                val gap = now - lastAcceptedMs
                if (gap > FaceMetricsConfig.MAX_TRACKING_LOSS_BEFORE_RESET_MS) {
                    samples.clear()
                    windowStartMs = now
                    samples += frame
                } else {
                    samples += frame
                }
                lastAcceptedMs = now
            }
            if (samples.size > requiredSamples * 4) {
                samples.removeAt(0)
            }
        } else {
            if (windowStartMs >= 0L && lastAcceptedMs >= 0L) {
                val gap = now - lastAcceptedMs
                if (gap > FaceMetricsConfig.MAX_TRACKING_LOSS_BEFORE_RESET_MS) {
                    samples.clear()
                    windowStartMs = -1L
                    lastAcceptedMs = -1L
                }
            }
        }

        val duration = continuousDurationMs()
        val stable = samples.size >= requiredSamples &&
            duration >= requiredDurationMs &&
            isStable(samples)

        if (stable) {
            val profile = aggregate(samples)
            completedProfile = profile
            return completedProgress(profile)
        }

        return FaceCalibrationProgress(
            uiState = uiState,
            acceptedSamples = samples.size,
            requiredSamples = requiredSamples,
            validContinuousDurationMs = duration,
            requiredDurationMs = requiredDurationMs,
            faceValid = faceCheck == FaceValidityReason.OK,
            irisValid = frame.leftIrisHorizontalRatio != null && frame.rightIrisHorizontalRatio != null,
            shouldersValid = if (requireUpperTorso) bodyOk else null,
            upperBodyEvidenceAgeMs = upperBodyAge(now),
        )
    }

    fun validateFace(frame: FaceFrame): FaceValidityReason {
        if (!frame.facePresent) return FaceValidityReason.NO_FACE
        if (frame.confidence < FaceMetricsConfig.MIN_FACE_CONFIDENCE) return FaceValidityReason.LOW_CONFIDENCE
        val scale = frame.faceScale ?: return FaceValidityReason.FACE_TOO_SMALL
        if (scale < FaceMetricsConfig.MIN_FACE_SCALE) return FaceValidityReason.FACE_TOO_SMALL
        if (scale > FaceMetricsConfig.MAX_FACE_SCALE) return FaceValidityReason.FACE_TOO_LARGE
        val cx = frame.faceCenterX ?: return FaceValidityReason.FACE_OFF_CENTER
        val cy = frame.faceCenterY ?: return FaceValidityReason.FACE_OFF_CENTER
        if (cx !in FaceMetricsConfig.CENTER_X_MIN..FaceMetricsConfig.CENTER_X_MAX ||
            cy !in FaceMetricsConfig.CENTER_Y_MIN..FaceMetricsConfig.CENTER_Y_MAX
        ) {
            return FaceValidityReason.FACE_OFF_CENTER
        }
        if (frame.leftEyeOpen == false && frame.rightEyeOpen == false) return FaceValidityReason.BLINKING
        if (frame.headYawDeg != null && abs(frame.headYawDeg) > FaceMetricsConfig.MAX_ABS_YAW_FOR_IRIS_DEG) {
            return FaceValidityReason.HEAD_ROTATION_EXCESSIVE
        }
        if (frame.leftIrisHorizontalRatio == null || frame.rightIrisHorizontalRatio == null) {
            return FaceValidityReason.IRIS_INVALID
        }
        return FaceValidityReason.OK
    }

    fun hasVisibleShouldersAndTorso(pose: UpperBodyPoseFrame): Boolean =
        hasVisibleShoulders(pose) && hasVisibleUpperTorso(pose)

    fun hasVisibleShoulders(pose: UpperBodyPoseFrame): Boolean {
        val ls = pose.landmarks[PoseLandmarkId.LEFT_SHOULDER] ?: return false
        val rs = pose.landmarks[PoseLandmarkId.RIGHT_SHOULDER] ?: return false
        val minVis = FaceMetricsConfig.INTERVIEW_MIN_SHOULDER_VISIBILITY
        return ls.visibility >= minVis && rs.visibility >= minVis
    }

    fun hasVisibleUpperTorso(pose: UpperBodyPoseFrame): Boolean {
        val minVis = FaceMetricsConfig.INTERVIEW_MIN_SHOULDER_VISIBILITY * 0.8f
        val lh = pose.landmarks[PoseLandmarkId.LEFT_HIP]
        val rh = pose.landmarks[PoseLandmarkId.RIGHT_HIP]
        return (lh != null && lh.visibility >= minVis) || (rh != null && rh.visibility >= minVis)
    }

    private fun upperBodyOk(nowMs: Long): Boolean {
        if (!requireUpperTorso) return true
        val evidence = lastUpperBody ?: return false
        if (evidence.sessionId != sessionId) return false
        val age = nowMs - evidence.timestampMs
        if (age < 0L || age > FaceMetricsConfig.UPPER_BODY_EVIDENCE_MAX_AGE_MS) return false
        return evidence.shouldersValid && evidence.torsoValid
    }

    private fun upperBodyAge(nowMs: Long): Long? {
        if (!requireUpperTorso) return null
        val evidence = lastUpperBody ?: return null
        return nowMs - evidence.timestampMs
    }

    private fun continuousDurationMs(): Long =
        if (windowStartMs >= 0L && lastAcceptedMs >= windowStartMs) {
            lastAcceptedMs - windowStartMs
        } else {
            0L
        }

    private fun uiHint(
        frame: FaceFrame,
        faceCheck: FaceValidityReason,
        bodyOk: Boolean,
    ): FaceCalibrationUiState = when {
        !frame.facePresent -> FaceCalibrationUiState.POSITION_FACE
        faceCheck == FaceValidityReason.FACE_TOO_SMALL -> FaceCalibrationUiState.MOVE_CLOSER
        faceCheck == FaceValidityReason.FACE_TOO_LARGE -> FaceCalibrationUiState.MOVE_FARTHER
        faceCheck == FaceValidityReason.FACE_OFF_CENTER ||
            faceCheck == FaceValidityReason.LOW_CONFIDENCE ||
            faceCheck == FaceValidityReason.NO_FACE -> FaceCalibrationUiState.POSITION_FACE
        faceCheck == FaceValidityReason.IRIS_INVALID ||
            faceCheck == FaceValidityReason.HEAD_ROTATION_EXCESSIVE ||
            faceCheck == FaceValidityReason.BLINKING -> FaceCalibrationUiState.LOOK_AT_CAMERA
        requireUpperTorso && !bodyOk -> FaceCalibrationUiState.SHOW_SHOULDERS
        else -> FaceCalibrationUiState.HOLD_STILL
    }

    private fun currentProgress(
        uiState: FaceCalibrationUiState,
    ): FaceCalibrationProgress {
        return FaceCalibrationProgress(
            uiState = uiState,
            acceptedSamples = samples.size,
            requiredSamples = requiredSamples,
            validContinuousDurationMs = continuousDurationMs(),
            requiredDurationMs = requiredDurationMs,
            faceValid = false,
            irisValid = false,
            shouldersValid = if (requireUpperTorso) false else null,
        )
    }

    private fun completedProgress(profile: FaceCalibrationProfile): FaceCalibrationProgress =
        FaceCalibrationProgress(
            uiState = FaceCalibrationUiState.COMPLETED,
            acceptedSamples = max(samples.size, requiredSamples),
            requiredSamples = requiredSamples,
            validContinuousDurationMs = requiredDurationMs,
            requiredDurationMs = requiredDurationMs,
            faceValid = true,
            irisValid = true,
            shouldersValid = if (requireUpperTorso) true else null,
            profile = profile,
        )

    private fun isStable(window: List<FaceFrame>): Boolean {
        fun spread(selector: (FaceFrame) -> Float?): Float {
            val values = window.mapNotNull(selector)
            if (values.isEmpty()) return Float.MAX_VALUE
            return values.maxOrNull()!! - values.minOrNull()!!
        }
        return spread { it.headYawDeg } <= FaceMetricsConfig.CALIBRATION_MAX_YAW_SPREAD_DEG &&
            spread { it.headPitchDeg } <= FaceMetricsConfig.CALIBRATION_MAX_PITCH_SPREAD_DEG &&
            spread { it.headRollDeg } <= FaceMetricsConfig.CALIBRATION_MAX_ROLL_SPREAD_DEG
    }

    private fun aggregate(window: List<FaceFrame>): FaceCalibrationProfile {
        fun agg(selector: (FaceFrame) -> Float?): Float =
            trimmedMean(window.mapNotNull(selector))

        return FaceCalibrationProfile(
            calibratedAtMs = window.last().timestampMs,
            baselineYawDeg = agg { it.headYawDeg },
            baselinePitchDeg = agg { it.headPitchDeg },
            baselineRollDeg = agg { it.headRollDeg },
            baselineLeftIrisHorizontalRatio = agg { it.leftIrisHorizontalRatio },
            baselineLeftIrisVerticalRatio = agg { it.leftIrisVerticalRatio },
            baselineRightIrisHorizontalRatio = agg { it.rightIrisHorizontalRatio },
            baselineRightIrisVerticalRatio = agg { it.rightIrisVerticalRatio },
            baselineFaceCenterX = agg { it.faceCenterX },
            baselineFaceCenterY = agg { it.faceCenterY },
            baselineFaceScale = agg { it.faceScale },
        )
    }

    companion object {
        fun trimmedMean(
            values: List<Float>,
            trimFraction: Float = FaceMetricsConfig.CALIBRATION_TRIM_FRACTION,
        ): Float {
            require(values.isNotEmpty())
            if (values.size < 3) return values.average().toFloat()
            val sorted = values.sorted()
            val trim = max(0, (sorted.size * trimFraction).toInt())
            val sliced = sorted.subList(trim, sorted.size - trim)
            val use = if (sliced.isEmpty()) sorted else sliced
            return use.average().toFloat()
        }

        fun median(values: List<Float>): Float {
            require(values.isNotEmpty())
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[mid - 1] + sorted[mid]) / 2f
            } else {
                sorted[mid]
            }
        }
    }
}
