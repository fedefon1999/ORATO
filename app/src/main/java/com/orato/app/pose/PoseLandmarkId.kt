package com.orato.app.pose

/**
 * MediaPipe BlazePose landmark indices used for upper-body overlay.
 * See: https://developers.google.com/mediapipe/solutions/vision/pose_landmarker
 */
enum class PoseLandmarkId(val mediapipeIndex: Int) {
    LEFT_SHOULDER(11),
    RIGHT_SHOULDER(12),
    LEFT_ELBOW(13),
    RIGHT_ELBOW(14),
    LEFT_WRIST(15),
    RIGHT_WRIST(16),
    LEFT_HIP(23),
    RIGHT_HIP(24),
}
