package com.orato.app.face

/**
 * In-memory holder for the active session's face calibration profile.
 * Cleared when leaving practice / cancelling calibration — never persisted.
 */
object PendingFaceCalibration {
    @Volatile
    private var profile: FaceCalibrationProfile? = null

    fun set(value: FaceCalibrationProfile) {
        profile = value
    }

    fun peek(): FaceCalibrationProfile? = profile

    fun clear() {
        profile = null
    }
}
