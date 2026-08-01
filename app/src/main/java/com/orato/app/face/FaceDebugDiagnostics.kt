package com.orato.app.face

/**
 * Compact debug diagnostics for Face Coach (debug builds only).
 */
data class FaceDebugDiagnostics(
    val faceConfidence: Float = 0f,
    val validityReason: FaceValidityReason = FaceValidityReason.NO_FACE,
    val relativeYawDeg: Float? = null,
    val relativePitchDeg: Float? = null,
    val relativeRollDeg: Float? = null,
    val leftIrisH: Float? = null,
    val leftIrisV: Float? = null,
    val rightIrisH: Float? = null,
    val rightIrisV: Float? = null,
    val gazeState: CameraGazeState = CameraGazeState.INVALID,
    val faceScale: Float? = null,
    val faceAnalysisFps: Float = 0f,
    val poseAnalysisFps: Float = 0f,
    val faceInferenceMs: Long = 0L,
    val poseInferenceMs: Long = 0L,
    val faceSkippedBusy: Long = 0L,
    val faceSkippedThrottle: Long = 0L,
    val poseSkippedBusy: Long = 0L,
    val poseSkippedThrottle: Long = 0L,
    val faceBusy: Boolean = false,
    val poseBusy: Boolean = false,
    val staleCallbackCount: Long = 0L,
    val cameraFps: Float = 0f,
    val droppedFrames: Long = 0L,
)

/**
 * Simple FPS / drop counter using wall-clock windows.
 */
class FaceDebugDiagnosticsTracker {
    private var faceAcceptsInWindow = 0
    private var poseAcceptsInWindow = 0
    private var cameraFramesInWindow = 0
    private var windowStartMs = 0L
    private var faceFps = 0f
    private var poseFps = 0f
    private var cameraFps = 0f

    fun onCameraFrame(nowMs: Long) {
        rollWindow(nowMs)
        cameraFramesInWindow++
    }

    fun onFaceAccepted(nowMs: Long) {
        rollWindow(nowMs)
        faceAcceptsInWindow++
    }

    fun onPoseAccepted(nowMs: Long) {
        rollWindow(nowMs)
        poseAcceptsInWindow++
    }

    fun snapshot(
        live: LiveFaceMetrics,
        faceClient: FaceLandmarkerClient?,
        poseSkippedBusy: Long = 0L,
        poseSkippedThrottle: Long = 0L,
        poseBusy: Boolean = false,
        poseInferenceMs: Long = 0L,
        droppedFrames: Long = 0L,
    ): FaceDebugDiagnostics {
        return FaceDebugDiagnostics(
            faceConfidence = live.confidence,
            validityReason = live.validityReason,
            relativeYawDeg = live.relativeYawDeg,
            relativePitchDeg = live.relativePitchDeg,
            relativeRollDeg = live.relativeRollDeg,
            leftIrisH = live.leftIrisHorizontalRatio,
            leftIrisV = live.leftIrisVerticalRatio,
            rightIrisH = live.rightIrisHorizontalRatio,
            rightIrisV = live.rightIrisVerticalRatio,
            gazeState = live.gazeState,
            faceScale = live.faceScale,
            faceAnalysisFps = faceFps,
            poseAnalysisFps = poseFps,
            faceInferenceMs = faceClient?.lastInferenceDurationMs ?: 0L,
            poseInferenceMs = poseInferenceMs,
            faceSkippedBusy = faceClient?.skippedWhileBusy ?: 0L,
            faceSkippedThrottle = faceClient?.skippedByThrottle ?: 0L,
            poseSkippedBusy = poseSkippedBusy,
            poseSkippedThrottle = poseSkippedThrottle,
            faceBusy = faceClient?.isBusy == true,
            poseBusy = poseBusy,
            staleCallbackCount = faceClient?.staleCallbackCount ?: 0L,
            cameraFps = cameraFps,
            droppedFrames = droppedFrames,
        )
    }

    private fun rollWindow(nowMs: Long) {
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
            return
        }
        val elapsed = nowMs - windowStartMs
        if (elapsed >= 1_000L) {
            val seconds = elapsed / 1000f
            faceFps = faceAcceptsInWindow / seconds
            poseFps = poseAcceptsInWindow / seconds
            cameraFps = cameraFramesInWindow / seconds
            faceAcceptsInWindow = 0
            poseAcceptsInWindow = 0
            cameraFramesInWindow = 0
            windowStartMs = nowMs
        }
    }
}
