package com.orato.app.vision

import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.FaceMetricsConfig

data class AnalyzerEligibility(
    val faceEligible: Boolean,
    val poseEligible: Boolean,
)

enum class SelectedVisualAnalyzer {
    FACE,
    POSE,
    NONE,
}

/**
 * Pure, deterministic frame-routing policy for visual analysis.
 *
 * Independent of CameraX and MediaPipe — unit-testable with synthetic timestamps.
 *
 * BODY_AND_FACE fairness: when both analyzers are eligible, round-robin alternates
 * so neither can consume every frame indefinitely.
 *
 * At most one analyzer is selected per CameraX frame (no concurrent ImageProxy ownership).
 */
class VisualFrameScheduler(
    private val mode: VisualAnalysisMode,
    private val faceMinIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
    private val poseMinIntervalMs: Long = FaceMetricsConfig.POSE_MIN_INTERVAL_MS,
) {
    private var lastFaceSubmittedMs: Long = 0L
    private var lastPoseSubmittedMs: Long = 0L
    private var faceInFlight: Boolean = false
    private var poseInFlight: Boolean = false
    private var faceRequestToken: Long = 0L
    private var poseRequestToken: Long = 0L
    private var nextRequestToken: Long = 1L
    private var preferFaceNext: Boolean = true
    private var closed: Boolean = false
    private var initialized: Boolean = true
    private var activeSessionId: Long = 0L

    @Volatile
    var roundRobinSelections: Long = 0L
        private set

    @Volatile
    var faceBusySkips: Long = 0L
        private set

    @Volatile
    var poseBusySkips: Long = 0L
        private set

    @Volatile
    var faceThrottleSkips: Long = 0L
        private set

    @Volatile
    var poseThrottleSkips: Long = 0L
        private set

    @Volatile
    var duplicateTimestampRejections: Long = 0L
        private set

    @Volatile
    var outOfOrderTimestampRejections: Long = 0L
        private set

    @Volatile
    var faceEligibleCount: Long = 0L
        private set

    @Volatile
    var poseEligibleCount: Long = 0L
        private set

    @Volatile
    var faceSubmittedCount: Long = 0L
        private set

    @Volatile
    var poseSubmittedCount: Long = 0L
        private set

    @Volatile
    var noneSelectedCount: Long = 0L
        private set

    fun beginSession(sessionId: Long) {
        activeSessionId = sessionId
        lastFaceSubmittedMs = 0L
        lastPoseSubmittedMs = 0L
        faceInFlight = false
        poseInFlight = false
        faceRequestToken = 0L
        poseRequestToken = 0L
        preferFaceNext = true
        closed = false
        initialized = true
    }

    fun close() {
        closed = true
        faceInFlight = false
        poseInFlight = false
        faceRequestToken = 0L
        poseRequestToken = 0L
    }

    val isFaceInFlight: Boolean get() = faceInFlight
    val isPoseInFlight: Boolean get() = poseInFlight
    val currentFaceRequestToken: Long get() = faceRequestToken
    val currentPoseRequestToken: Long get() = poseRequestToken

    /**
     * True only when a subsequent [select] for Face would accept [timestampMs].
     */
    fun canAcceptFace(timestampMs: Long, sessionId: Long = activeSessionId): Boolean {
        if (!initialized || closed || sessionId != activeSessionId) return false
        if (faceInFlight) return false
        if (!isTimestampAcceptable(timestampMs, lastFaceSubmittedMs, faceMinIntervalMs)) return false
        return true
    }

    fun canAcceptPose(timestampMs: Long, sessionId: Long = activeSessionId): Boolean {
        if (!initialized || closed || sessionId != activeSessionId) return false
        if (poseInFlight) return false
        if (!isTimestampAcceptable(timestampMs, lastPoseSubmittedMs, poseMinIntervalMs)) return false
        return true
    }

    fun eligibility(
        timestampMs: Long,
        faceAvailable: Boolean,
        poseAvailable: Boolean,
        sessionId: Long = activeSessionId,
    ): AnalyzerEligibility {
        val face = when (mode) {
            VisualAnalysisMode.BODY_ONLY -> false
            VisualAnalysisMode.FACE_ONLY,
            VisualAnalysisMode.BODY_AND_FACE,
            -> faceAvailable && canAcceptFace(timestampMs, sessionId)
        }
        val pose = when (mode) {
            VisualAnalysisMode.FACE_ONLY -> false
            VisualAnalysisMode.BODY_ONLY,
            VisualAnalysisMode.BODY_AND_FACE,
            -> poseAvailable && canAcceptPose(timestampMs, sessionId)
        }
        if (face) faceEligibleCount++
        if (pose) poseEligibleCount++
        if (faceAvailable && faceInFlight) faceBusySkips++
        if (poseAvailable && poseInFlight) poseBusySkips++
        if (faceAvailable && !faceInFlight && !canAcceptFace(timestampMs, sessionId) &&
            mode != VisualAnalysisMode.BODY_ONLY
        ) {
            if (lastFaceSubmittedMs > 0L && timestampMs == lastFaceSubmittedMs) {
                duplicateTimestampRejections++
            } else if (lastFaceSubmittedMs > 0L && timestampMs < lastFaceSubmittedMs) {
                outOfOrderTimestampRejections++
            } else {
                faceThrottleSkips++
            }
        }
        if (poseAvailable && !poseInFlight && !canAcceptPose(timestampMs, sessionId) &&
            mode != VisualAnalysisMode.FACE_ONLY
        ) {
            if (lastPoseSubmittedMs > 0L && timestampMs == lastPoseSubmittedMs) {
                duplicateTimestampRejections++
            } else if (lastPoseSubmittedMs > 0L && timestampMs < lastPoseSubmittedMs) {
                outOfOrderTimestampRejections++
            } else {
                poseThrottleSkips++
            }
        }
        return AnalyzerEligibility(faceEligible = face, poseEligible = pose)
    }

    /**
     * Selects at most one analyzer for this CameraX frame.
     * @return selection and the request token to associate with the in-flight submit
     */
    fun select(
        timestampMs: Long,
        faceAvailable: Boolean,
        poseAvailable: Boolean,
        sessionId: Long = activeSessionId,
    ): Pair<SelectedVisualAnalyzer, Long> {
        val elig = eligibility(timestampMs, faceAvailable, poseAvailable, sessionId)
        val selected = when (mode) {
            VisualAnalysisMode.BODY_ONLY ->
                if (elig.poseEligible) SelectedVisualAnalyzer.POSE else SelectedVisualAnalyzer.NONE
            VisualAnalysisMode.FACE_ONLY ->
                if (elig.faceEligible) SelectedVisualAnalyzer.FACE else SelectedVisualAnalyzer.NONE
            VisualAnalysisMode.BODY_AND_FACE -> when {
                elig.faceEligible && !elig.poseEligible -> SelectedVisualAnalyzer.FACE
                elig.poseEligible && !elig.faceEligible -> SelectedVisualAnalyzer.POSE
                elig.faceEligible && elig.poseEligible -> {
                    roundRobinSelections++
                    if (preferFaceNext) {
                        preferFaceNext = false
                        SelectedVisualAnalyzer.FACE
                    } else {
                        preferFaceNext = true
                        SelectedVisualAnalyzer.POSE
                    }
                }
                else -> SelectedVisualAnalyzer.NONE
            }
        }
        return when (selected) {
            SelectedVisualAnalyzer.FACE -> {
                val token = markFaceSubmitted(timestampMs)
                faceSubmittedCount++
                selected to token
            }
            SelectedVisualAnalyzer.POSE -> {
                val token = markPoseSubmitted(timestampMs)
                poseSubmittedCount++
                selected to token
            }
            SelectedVisualAnalyzer.NONE -> {
                noneSelectedCount++
                selected to 0L
            }
        }
    }

    fun onFaceCompleted(requestToken: Long, sessionId: Long): Boolean {
        if (sessionId != activeSessionId) return false
        if (requestToken == 0L || requestToken != faceRequestToken) return false
        if (!faceInFlight) return false
        faceInFlight = false
        faceRequestToken = 0L
        return true
    }

    fun onPoseCompleted(requestToken: Long, sessionId: Long): Boolean {
        if (sessionId != activeSessionId) return false
        if (requestToken == 0L || requestToken != poseRequestToken) return false
        if (!poseInFlight) return false
        poseInFlight = false
        poseRequestToken = 0L
        return true
    }

    fun onFaceError(requestToken: Long, sessionId: Long): Boolean =
        onFaceCompleted(requestToken, sessionId)

    fun onPoseError(requestToken: Long, sessionId: Long): Boolean =
        onPoseCompleted(requestToken, sessionId)

    private fun markFaceSubmitted(timestampMs: Long): Long {
        val token = nextRequestToken++
        faceInFlight = true
        faceRequestToken = token
        lastFaceSubmittedMs = timestampMs
        return token
    }

    private fun markPoseSubmitted(timestampMs: Long): Long {
        val token = nextRequestToken++
        poseInFlight = true
        poseRequestToken = token
        lastPoseSubmittedMs = timestampMs
        return token
    }

    private fun isTimestampAcceptable(timestampMs: Long, lastSubmittedMs: Long, minIntervalMs: Long): Boolean {
        if (lastSubmittedMs <= 0L) return true
        if (timestampMs == lastSubmittedMs) return false
        if (timestampMs < lastSubmittedMs) return false
        return timestampMs - lastSubmittedMs >= minIntervalMs
    }
}
