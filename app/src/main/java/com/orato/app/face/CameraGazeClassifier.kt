package com.orato.app.face

import kotlin.math.abs

/**
 * Camera-gaze classifier combining head pose and iris evidence.
 *
 * Metric name: "Sguardo verso la videocamera".
 * Does not claim scientific eye tracking.
 */
class CameraGazeClassifier(
    private val config: FaceMetricsConfig = FaceMetricsConfig,
) {
    private var state: CameraGazeState = CameraGazeState.INVALID
    private var candidate: CameraGazeState = CameraGazeState.INVALID
    private var candidateSinceMs: Long = 0L
    private var lastValidState: CameraGazeState = CameraGazeState.INVALID
    private var lastValidAtMs: Long = 0L
    private var lastInvalidAtMs: Long = 0L

    fun reset() {
        state = CameraGazeState.INVALID
        candidate = CameraGazeState.INVALID
        candidateSinceMs = 0L
        lastValidState = CameraGazeState.INVALID
        lastValidAtMs = 0L
        lastInvalidAtMs = 0L
    }

    fun currentState(): CameraGazeState = state

    /**
     * @return updated gaze state after applying hysteresis / confirmation time.
     */
    fun update(
        timestampMs: Long,
        relativeYawDeg: Float?,
        relativePitchDeg: Float?,
        relativeRollDeg: Float?,
        leftIrisHDelta: Float?,
        leftIrisVDelta: Float?,
        rightIrisHDelta: Float?,
        rightIrisVDelta: Float?,
        leftEyeOpen: Boolean?,
        rightEyeOpen: Boolean?,
        faceConfidence: Float,
        framingValid: Boolean,
    ): CameraGazeState {
        val instant = classifyInstant(
            relativeYawDeg = relativeYawDeg,
            relativePitchDeg = relativePitchDeg,
            relativeRollDeg = relativeRollDeg,
            leftIrisHDelta = leftIrisHDelta,
            leftIrisVDelta = leftIrisVDelta,
            rightIrisHDelta = rightIrisHDelta,
            rightIrisVDelta = rightIrisVDelta,
            leftEyeOpen = leftEyeOpen,
            rightEyeOpen = rightEyeOpen,
            faceConfidence = faceConfidence,
            framingValid = framingValid,
        )

        if (instant == CameraGazeState.INVALID) {
            lastInvalidAtMs = timestampMs
            val gap = timestampMs - lastValidAtMs
            if (lastValidAtMs > 0L &&
                gap <= config.GAZE_INVALID_BRIDGE_MS &&
                lastValidState != CameraGazeState.INVALID
            ) {
                // Bridge brief invalid gaps only when surrounding states agree.
                return state
            }
            transitionTo(CameraGazeState.INVALID, timestampMs)
            return state
        }

        lastValidState = instant
        lastValidAtMs = timestampMs

        if (instant == state) {
            candidate = instant
            candidateSinceMs = timestampMs
            return state
        }

        if (instant != candidate) {
            candidate = instant
            candidateSinceMs = timestampMs
            return state
        }

        val needed = when (instant) {
            CameraGazeState.TOWARD_CAMERA -> config.GAZE_ENTER_TOWARD_MS
            CameraGazeState.AWAY -> config.GAZE_ENTER_AWAY_MS
            CameraGazeState.INVALID -> 0L
        }
        if (timestampMs - candidateSinceMs >= needed) {
            transitionTo(instant, timestampMs)
        }
        return state
    }

    /**
     * Instantaneous classification without temporal smoothing.
     * Requires both head and eye evidence — neither alone is sufficient.
     */
    fun classifyInstant(
        relativeYawDeg: Float?,
        relativePitchDeg: Float?,
        relativeRollDeg: Float?,
        leftIrisHDelta: Float?,
        leftIrisVDelta: Float?,
        rightIrisHDelta: Float?,
        rightIrisVDelta: Float?,
        leftEyeOpen: Boolean?,
        rightEyeOpen: Boolean?,
        faceConfidence: Float,
        framingValid: Boolean,
    ): CameraGazeState {
        if (!framingValid || faceConfidence < FaceMetricsConfig.MIN_FACE_CONFIDENCE) {
            return CameraGazeState.INVALID
        }
        // Blink must not count as gaze-away.
        if (leftEyeOpen == false && rightEyeOpen == false) {
            return CameraGazeState.INVALID
        }
        if (relativeYawDeg == null || relativePitchDeg == null) {
            return CameraGazeState.INVALID
        }

        val headToward =
            abs(relativeYawDeg) <= config.GAZE_MAX_REL_YAW_DEG &&
                abs(relativePitchDeg) <= config.GAZE_MAX_REL_PITCH_DEG &&
                (relativeRollDeg == null || abs(relativeRollDeg) <= config.GAZE_MAX_REL_ROLL_DEG)

        val irisDeltas = listOfNotNull(
            leftIrisHDelta,
            leftIrisVDelta,
            rightIrisHDelta,
            rightIrisVDelta,
        )
        if (irisDeltas.isEmpty()) {
            return CameraGazeState.INVALID
        }

        val bothEyes = leftIrisHDelta != null && rightIrisHDelta != null
        val irisToward = if (bothEyes) {
            abs(leftIrisHDelta!!) <= config.GAZE_MAX_IRIS_HORIZONTAL_DELTA &&
                abs(rightIrisHDelta!!) <= config.GAZE_MAX_IRIS_HORIZONTAL_DELTA &&
                (leftIrisVDelta == null || abs(leftIrisVDelta) <= config.GAZE_MAX_IRIS_VERTICAL_DELTA) &&
                (rightIrisVDelta == null || abs(rightIrisVDelta) <= config.GAZE_MAX_IRIS_VERTICAL_DELTA)
        } else {
            irisDeltas.all { abs(it) <= config.GAZE_MAX_IRIS_HORIZONTAL_DELTA }
        }

        return when {
            headToward && irisToward -> CameraGazeState.TOWARD_CAMERA
            else -> CameraGazeState.AWAY
        }
    }

    private fun transitionTo(next: CameraGazeState, timestampMs: Long) {
        state = next
        candidate = next
        candidateSinceMs = timestampMs
    }
}
