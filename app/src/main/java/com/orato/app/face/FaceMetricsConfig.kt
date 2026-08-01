package com.orato.app.face

/**
 * Configurable Face Coach thresholds. Initial values require physical-device validation.
 */
object FaceMetricsConfig {
    // Framing
    const val MIN_FACE_CONFIDENCE = 0.55f
    const val MIN_FACE_SCALE = 0.12f
    const val MAX_FACE_SCALE = 0.55f
    const val CENTER_X_MIN = 0.30f
    const val CENTER_X_MAX = 0.70f
    const val CENTER_Y_MIN = 0.25f
    const val CENTER_Y_MAX = 0.65f
    const val MAX_ABS_YAW_FOR_IRIS_DEG = 25f
    const val MAX_ABS_PITCH_FOR_IRIS_DEG = 20f
    const val MAX_ABS_ROLL_FOR_IRIS_DEG = 20f

    // Calibration
    const val CALIBRATION_REQUIRED_ACCEPTED_SAMPLES = 18
    const val CALIBRATION_MAX_YAW_SPREAD_DEG = 8f
    const val CALIBRATION_MAX_PITCH_SPREAD_DEG = 8f
    const val CALIBRATION_MAX_ROLL_SPREAD_DEG = 8f
    const val CALIBRATION_TRIM_FRACTION = 0.15f

    // Interview upper-body (pose) gates during calibration
    const val INTERVIEW_MIN_SHOULDER_VISIBILITY = 0.45f

    // Gaze
    const val GAZE_MAX_REL_YAW_DEG = 12f
    const val GAZE_MAX_REL_PITCH_DEG = 10f
    const val GAZE_MAX_REL_ROLL_DEG = 12f
    const val GAZE_MAX_IRIS_HORIZONTAL_DELTA = 0.12f
    const val GAZE_MAX_IRIS_VERTICAL_DELTA = 0.12f
    const val GAZE_ENTER_TOWARD_MS = 250L
    const val GAZE_ENTER_AWAY_MS = 300L
    const val GAZE_INVALID_BRIDGE_MS = 150L
    const val SIGNIFICANT_GAZE_AWAY_MS = 500L
    const val STALE_RESULT_MS = 400L

    // Head movement
    const val HEAD_CENTERED_YAW_DEG = 10f
    const val HEAD_CENTERED_PITCH_DEG = 10f
    const val HEAD_CENTERED_ROLL_DEG = 10f
    const val LARGE_YAW_EVENT_DEG = 22f
    const val LARGE_PITCH_EVENT_DEG = 18f
    const val LARGE_ROLL_EVENT_DEG = 18f
    const val HEAD_EVENT_HYSTERESIS_DEG = 4f
    const val HEAD_EVENT_REFRACTORY_MS = 600L
    const val HEAD_ANGLE_EMA_ALPHA = 0.35f
    const val STABLE_HEAD_MAX_SPEED_DEG_PER_S = 18f

    // Eye closure (expose only when reliable)
    const val PROLONGED_EYE_CLOSURE_MS = 800L
    const val EYE_CLOSURE_METRICS_ENABLED = false

    // Analysis throttling (timestamp-based; not tied to assumed FPS)
    const val FACE_MIN_INTERVAL_MS = 80L // ~12.5 Hz target band 10–15
    const val POSE_MIN_INTERVAL_MS = 100L // ~10 Hz target band 8–12
}
