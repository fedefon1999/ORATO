package com.orato.app.pose

/**
 * A single upper-body landmark in MediaPipe normalized image coordinates (0–1).
 */
data class NormalizedLandmarkPoint(
    val id: PoseLandmarkId,
    val x: Float,
    val y: Float,
    val visibility: Float,
)

/**
 * One processed pose frame ready for overlay rendering.
 *
 * [imageWidth] / [imageHeight] are the dimensions of the image fed to MediaPipe
 * (after rotation), used to map normalized coords onto the preview with FILL_CENTER.
 */
data class UpperBodyPoseFrame(
    val landmarks: Map<PoseLandmarkId, NormalizedLandmarkPoint>,
    val imageWidth: Int,
    val imageHeight: Int,
)

sealed interface PoseDetectionStatus {
    data object Initializing : PoseDetectionStatus
    data object Detected : PoseDetectionStatus
    data object Insufficient : PoseDetectionStatus
    data class Error(val message: String) : PoseDetectionStatus
}

object PoseVisibility {
    const val MIN_VISIBILITY = 0.5f

    private val torsoLandmarks = listOf(
        PoseLandmarkId.LEFT_SHOULDER,
        PoseLandmarkId.RIGHT_SHOULDER,
        PoseLandmarkId.LEFT_HIP,
        PoseLandmarkId.RIGHT_HIP,
    )

    fun hasSufficientTorsoVisibility(
        landmarks: Map<PoseLandmarkId, NormalizedLandmarkPoint>,
    ): Boolean {
        return torsoLandmarks.all { id ->
            val point = landmarks[id] ?: return false
            point.visibility >= MIN_VISIBILITY
        }
    }
}
