package com.orato.app.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.FaceFrame
import com.orato.app.face.FaceLandmarkerClient
import com.orato.app.face.FaceMetricsConfig
import com.orato.app.pose.PoseLandmarkerClient
import com.orato.app.pose.UpperBodyPoseFrame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared CameraX frame router for scenario visual analysis modes.
 *
 * BODY_ONLY  → Pose only
 * FACE_ONLY  → Face only
 * BODY_AND_FACE → both, with independent timestamp throttling and busy guards
 *
 * Uses STRATEGY_KEEP_ONLY_LATEST at the ImageAnalysis level; this coordinator
 * never queues frames.
 */
class VisualAnalysisCoordinator(
    private val mode: VisualAnalysisMode,
    private val poseClient: PoseLandmarkerClient?,
    private val faceClient: FaceLandmarkerClient?,
    private val onPoseFrame: ((UpperBodyPoseFrame) -> Unit)? = null,
    private val onFaceFrame: ((FaceFrame) -> Unit)? = null,
    private val onCameraFrame: (() -> Unit)? = null,
    private val poseMinIntervalMs: Long = FaceMetricsConfig.POSE_MIN_INTERVAL_MS,
    private val faceMinIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
) : ImageAnalysis.Analyzer {

    private val closed = AtomicBoolean(false)
    private val poseBusy = AtomicBoolean(false)
    private val lastPoseAcceptedMs = AtomicLong(0L)

    @Volatile
    var poseSkippedBusy: Long = 0L
        private set

    @Volatile
    var poseSkippedThrottle: Long = 0L
        private set

    @Volatile
    var poseInferenceMs: Long = 0L
        private set

    @Volatile
    var droppedOrSkippedTotal: Long = 0L
        private set

    val isPoseBusy: Boolean
        get() = poseBusy.get()

    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }
        onCameraFrame?.invoke()

        when (mode) {
            VisualAnalysisMode.BODY_ONLY -> routePoseOnly(imageProxy)
            VisualAnalysisMode.FACE_ONLY -> routeFaceOnly(imageProxy)
            VisualAnalysisMode.BODY_AND_FACE -> routeBoth(imageProxy)
        }
    }

    fun close() {
        closed.set(true)
    }

    private fun routePoseOnly(imageProxy: ImageProxy) {
        val client = poseClient
        if (client == null || !client.isReady) {
            droppedOrSkippedTotal++
            imageProxy.close()
            return
        }
        if (!acceptPoseSlot()) {
            imageProxy.close()
            return
        }
        val started = SystemClock.uptimeMillis()
        try {
            client.detectLiveStream(imageProxy)
        } finally {
            poseInferenceMs = SystemClock.uptimeMillis() - started
            poseBusy.set(false)
        }
    }

    private fun routeFaceOnly(imageProxy: ImageProxy) {
        val client = faceClient
        if (client == null || !client.isReady) {
            droppedOrSkippedTotal++
            imageProxy.close()
            return
        }
        client.detectLiveStream(imageProxy, faceMinIntervalMs)
    }

    /**
     * Alternating eligibility: each analyzer independently decides via throttle + busy.
     * Pose consumes a copied path only when eligible; Face gets the ImageProxy when Pose skips,
     * otherwise Pose runs on the proxy (Face skips that frame — KEEP_ONLY_LATEST + independent rates).
     *
     * For BODY_AND_FACE we prefer Face when both are eligible because iris benefits from
     * slightly higher rate; Pose runs on the next eligible frame it can claim.
     */
    private fun routeBoth(imageProxy: ImageProxy) {
        val face = faceClient
        val pose = poseClient
        val faceReady = face != null && face.isReady
        val poseReady = pose != null && pose.isReady

        if (!faceReady && !poseReady) {
            droppedOrSkippedTotal++
            imageProxy.close()
            return
        }

        val now = SystemClock.uptimeMillis()
        val faceEligible = faceReady && face!!.canAcceptFrame(now, faceMinIntervalMs) && !face.isBusy
        val poseEligible = poseReady && canAcceptPose(now) && !poseBusy.get()

        when {
            faceEligible && !poseEligible -> {
                face.detectLiveStream(imageProxy, faceMinIntervalMs)
            }
            poseEligible && !faceEligible -> {
                runPose(imageProxy)
            }
            faceEligible && poseEligible -> {
                // Face first this frame; pose will take a subsequent frame.
                face.detectLiveStream(imageProxy, faceMinIntervalMs)
            }
            else -> {
                droppedOrSkippedTotal++
                imageProxy.close()
            }
        }
    }

    private fun runPose(imageProxy: ImageProxy) {
        val client = poseClient ?: run {
            imageProxy.close()
            return
        }
        if (!acceptPoseSlot()) {
            imageProxy.close()
            return
        }
        val started = SystemClock.uptimeMillis()
        try {
            client.detectLiveStream(imageProxy)
        } finally {
            poseInferenceMs = SystemClock.uptimeMillis() - started
            poseBusy.set(false)
        }
    }

    private fun acceptPoseSlot(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!canAcceptPose(now)) {
            poseSkippedThrottle++
            droppedOrSkippedTotal++
            return false
        }
        if (!poseBusy.compareAndSet(false, true)) {
            poseSkippedBusy++
            droppedOrSkippedTotal++
            return false
        }
        lastPoseAcceptedMs.set(now)
        return true
    }

    private fun canAcceptPose(now: Long): Boolean {
        val last = lastPoseAcceptedMs.get()
        return last == 0L || now - last >= poseMinIntervalMs
    }

    companion object {
        private const val TAG = "VisualAnalysisCoord"

        fun createClients(
            context: Context,
            mode: VisualAnalysisMode,
            sessionId: Long,
            onPoseFrame: (UpperBodyPoseFrame) -> Unit,
            onPoseError: (String) -> Unit,
            onFaceFrame: (FaceFrame) -> Unit,
            onFaceError: (String) -> Unit,
        ): Pair<PoseLandmarkerClient?, FaceLandmarkerClient?> {
            val pose = if (mode == VisualAnalysisMode.BODY_ONLY || mode == VisualAnalysisMode.BODY_AND_FACE) {
                PoseLandmarkerClient(
                    context = context,
                    onResult = onPoseFrame,
                    onError = onPoseError,
                    sessionIdProvider = { sessionId },
                ).also { it.initialize(); it.beginSession(sessionId) }
            } else {
                null
            }
            val face = if (mode == VisualAnalysisMode.FACE_ONLY || mode == VisualAnalysisMode.BODY_AND_FACE) {
                FaceLandmarkerClient(
                    context = context,
                    onResult = onFaceFrame,
                    onError = onFaceError,
                    sessionIdProvider = { sessionId },
                ).also { it.initialize(); it.beginSession(sessionId) }
            } else {
                null
            }
            Log.d(TAG, "Initialized mode=$mode pose=${pose != null} face=${face != null}")
            return pose to face
        }
    }
}

/**
 * Peek whether Face client would accept without consuming a frame.
 */
internal fun FaceLandmarkerClient.canAcceptFrame(nowMs: Long, minIntervalMs: Long): Boolean {
    // Approximate: if busy, no; throttle uses lastAccepted inside detectLiveStream.
    return !isBusy
}
