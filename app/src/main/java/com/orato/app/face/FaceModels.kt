package com.orato.app.face

/**
 * Domain face frame — no MediaPipe types leak past the face infrastructure layer.
 */
data class FaceFrame(
    val timestampMs: Long,
    val facePresent: Boolean,
    val faceCenterX: Float?,
    val faceCenterY: Float?,
    val faceScale: Float?,
    val headYawDeg: Float?,
    val headPitchDeg: Float?,
    val headRollDeg: Float?,
    val leftIrisHorizontalRatio: Float?,
    val leftIrisVerticalRatio: Float?,
    val rightIrisHorizontalRatio: Float?,
    val rightIrisVerticalRatio: Float?,
    val leftEyeOpen: Boolean?,
    val rightEyeOpen: Boolean?,
    val confidence: Float,
)

data class FaceCalibrationProfile(
    val calibratedAtMs: Long,
    val baselineYawDeg: Float,
    val baselinePitchDeg: Float,
    val baselineRollDeg: Float,
    val baselineLeftIrisHorizontalRatio: Float,
    val baselineLeftIrisVerticalRatio: Float,
    val baselineRightIrisHorizontalRatio: Float,
    val baselineRightIrisVerticalRatio: Float,
    val baselineFaceCenterX: Float,
    val baselineFaceCenterY: Float,
    val baselineFaceScale: Float,
)

enum class CameraGazeState {
    TOWARD_CAMERA,
    AWAY,
    INVALID,
}

sealed interface FaceDetectionStatus {
    data object Initializing : FaceDetectionStatus
    data object Detected : FaceDetectionStatus
    data object Insufficient : FaceDetectionStatus
    data class Error(val message: String) : FaceDetectionStatus
}

enum class FaceValidityReason {
    OK,
    NO_FACE,
    LOW_CONFIDENCE,
    FACE_TOO_SMALL,
    FACE_TOO_LARGE,
    FACE_OFF_CENTER,
    IRIS_INVALID,
    HEAD_ROTATION_EXCESSIVE,
    BLINKING,
    STALE,
}

/**
 * Live snapshot for debug overlay and calibration feedback.
 */
data class LiveFaceMetrics(
    val facePresent: Boolean = false,
    val validTracking: Boolean = false,
    val validityReason: FaceValidityReason = FaceValidityReason.NO_FACE,
    val confidence: Float = 0f,
    val faceScale: Float? = null,
    val faceCenterX: Float? = null,
    val faceCenterY: Float? = null,
    val relativeYawDeg: Float? = null,
    val relativePitchDeg: Float? = null,
    val relativeRollDeg: Float? = null,
    val leftIrisHorizontalRatio: Float? = null,
    val leftIrisVerticalRatio: Float? = null,
    val rightIrisHorizontalRatio: Float? = null,
    val rightIrisVerticalRatio: Float? = null,
    val gazeState: CameraGazeState = CameraGazeState.INVALID,
    val leftEyeOpen: Boolean? = null,
    val rightEyeOpen: Boolean? = null,
)
