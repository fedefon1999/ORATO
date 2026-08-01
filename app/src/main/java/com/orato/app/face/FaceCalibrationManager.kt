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

data class FaceCalibrationProgress(
    val uiState: FaceCalibrationUiState,
    val acceptedSamples: Int,
    val requiredSamples: Int,
    val faceValid: Boolean,
    val irisValid: Boolean,
    val shouldersValid: Boolean?,
    val profile: FaceCalibrationProfile? = null,
) {
    val progressFraction: Float
        get() = if (requiredSamples <= 0) {
            0f
        } else {
            acceptedSamples.toFloat() / requiredSamples.toFloat()
        }
}

/**
 * Session-specific face calibration. Does not persist biometric geometry.
 */
class FaceCalibrationManager(
    private val requireUpperTorso: Boolean = false,
    private val requiredSamples: Int = FaceMetricsConfig.CALIBRATION_REQUIRED_ACCEPTED_SAMPLES,
) {
    private val samples = mutableListOf<FaceFrame>()
    private var completedProfile: FaceCalibrationProfile? = null
    private var lastShouldersValid: Boolean? = if (requireUpperTorso) false else null

    fun reset() {
        samples.clear()
        completedProfile = null
        lastShouldersValid = if (requireUpperTorso) false else null
    }

    fun onPoseFrame(pose: UpperBodyPoseFrame?) {
        if (!requireUpperTorso) {
            lastShouldersValid = null
            return
        }
        lastShouldersValid = pose != null && hasVisibleShouldersAndTorso(pose)
    }

    fun process(frame: FaceFrame): FaceCalibrationProgress {
        completedProfile?.let { profile ->
            return FaceCalibrationProgress(
                uiState = FaceCalibrationUiState.COMPLETED,
                acceptedSamples = requiredSamples,
                requiredSamples = requiredSamples,
                faceValid = true,
                irisValid = true,
                shouldersValid = lastShouldersValid,
                profile = profile,
            )
        }

        val faceCheck = validateFace(frame)
        val shouldersOk = if (requireUpperTorso) lastShouldersValid == true else true

        val uiState = when {
            !frame.facePresent -> FaceCalibrationUiState.POSITION_FACE
            faceCheck == FaceValidityReason.FACE_TOO_SMALL -> FaceCalibrationUiState.MOVE_CLOSER
            faceCheck == FaceValidityReason.FACE_TOO_LARGE -> FaceCalibrationUiState.MOVE_FARTHER
            faceCheck == FaceValidityReason.FACE_OFF_CENTER ||
                faceCheck == FaceValidityReason.LOW_CONFIDENCE ||
                faceCheck == FaceValidityReason.NO_FACE -> FaceCalibrationUiState.POSITION_FACE
            faceCheck == FaceValidityReason.IRIS_INVALID ||
                faceCheck == FaceValidityReason.HEAD_ROTATION_EXCESSIVE ||
                faceCheck == FaceValidityReason.BLINKING -> FaceCalibrationUiState.LOOK_AT_CAMERA
            requireUpperTorso && !shouldersOk -> FaceCalibrationUiState.SHOW_SHOULDERS
            else -> FaceCalibrationUiState.HOLD_STILL
        }

        val accept = faceCheck == FaceValidityReason.OK && shouldersOk &&
            frame.leftIrisHorizontalRatio != null &&
            frame.rightIrisHorizontalRatio != null &&
            frame.headYawDeg != null &&
            frame.headPitchDeg != null &&
            frame.headRollDeg != null

        if (accept) {
            samples += frame
            if (samples.size > requiredSamples * 3) {
                samples.removeAt(0)
            }
        } else if (samples.isNotEmpty() && faceCheck != FaceValidityReason.OK) {
            // Unstable — drop recent unstable streak by trimming one sample
            if (samples.size > requiredSamples) {
                samples.removeAt(samples.lastIndex)
            }
        }

        val stableWindow = samples.takeLast(requiredSamples)
        val stable = stableWindow.size >= requiredSamples && isStable(stableWindow)

        if (stable) {
            val profile = aggregate(stableWindow)
            completedProfile = profile
            return FaceCalibrationProgress(
                uiState = FaceCalibrationUiState.COMPLETED,
                acceptedSamples = requiredSamples,
                requiredSamples = requiredSamples,
                faceValid = true,
                irisValid = true,
                shouldersValid = lastShouldersValid,
                profile = profile,
            )
        }

        return FaceCalibrationProgress(
            uiState = uiState,
            acceptedSamples = min(stableWindow.size, requiredSamples),
            requiredSamples = requiredSamples,
            faceValid = faceCheck == FaceValidityReason.OK,
            irisValid = frame.leftIrisHorizontalRatio != null && frame.rightIrisHorizontalRatio != null,
            shouldersValid = lastShouldersValid,
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

    fun hasVisibleShouldersAndTorso(pose: UpperBodyPoseFrame): Boolean {
        val ls = pose.landmarks[PoseLandmarkId.LEFT_SHOULDER] ?: return false
        val rs = pose.landmarks[PoseLandmarkId.RIGHT_SHOULDER] ?: return false
        val lh = pose.landmarks[PoseLandmarkId.LEFT_HIP]
        val rh = pose.landmarks[PoseLandmarkId.RIGHT_HIP]
        val minVis = FaceMetricsConfig.INTERVIEW_MIN_SHOULDER_VISIBILITY
        if (ls.visibility < minVis || rs.visibility < minVis) return false
        // Upper torso: at least one hip visible (hands not required)
        val hipOk = (lh != null && lh.visibility >= minVis * 0.8f) ||
            (rh != null && rh.visibility >= minVis * 0.8f)
        return hipOk
    }

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
        fun trimmedMean(values: List<Float>, trimFraction: Float = FaceMetricsConfig.CALIBRATION_TRIM_FRACTION): Float {
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
