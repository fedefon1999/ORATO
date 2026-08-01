package com.orato.app.face

/**
 * MediaPipe Face Mesh landmark indices used by Face Coach.
 * Kept inside the face infrastructure layer — never exposed to UI/report models.
 *
 * Reference: MediaPipe Face Landmarker (478 landmarks with iris).
 */
internal object FaceLandmarkIndex {
    // Left eye (subject's left)
    const val LEFT_EYE_OUTER = 33
    const val LEFT_EYE_INNER = 133
    const val LEFT_EYE_UPPER = 159
    const val LEFT_EYE_LOWER = 145
    const val LEFT_IRIS_CENTER = 468

    // Right eye (subject's right)
    const val RIGHT_EYE_OUTER = 263
    const val RIGHT_EYE_INNER = 362
    const val RIGHT_EYE_UPPER = 386
    const val RIGHT_EYE_LOWER = 374
    const val RIGHT_IRIS_CENTER = 473

    // Face oval extremes for scale / center
    const val FOREHEAD = 10
    const val CHIN = 152
    const val LEFT_CHEEK = 234
    const val RIGHT_CHEEK = 454

    // Blendshape names (MediaPipe FaceBlendshapes)
    const val BS_EYE_BLINK_LEFT = "eyeBlinkLeft"
    const val BS_EYE_BLINK_RIGHT = "eyeBlinkRight"
}
