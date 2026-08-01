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
 * Shared CameraX frame router for a single visual analysis mode.
 *
 * BODY_ONLY → Pose only
 * FACE_ONLY → Face only
 *
 * Pose and Face are never initialized or run together.
 * One ImageAnalysis stream, STRATEGY_KEEP_ONLY_LATEST, ImageProxy closed exactly once.
 */
class VisualAnalysisCoordinator(
    private val mode: VisualAnalysisMode,
    private val poseClient: PoseLandmarkerClient?,
    private val faceClient: FaceLandmarkerClient?,
    private val sessionId: Long,
    private val onCameraFrame: ((captureTimestampMs: Long) -> Unit)? = null,
    private val faceMinIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
    private val poseMinIntervalMs: Long = FaceMetricsConfig.POSE_MIN_INTERVAL_MS,
) : ImageAnalysis.Analyzer {

    private val closed = AtomicBoolean(false)
    private var windowStartMs = 0L
    private var framesInWindow = 0
    private var completedInWindow = 0

    @Volatile var cameraInputFps: Float = 0f; private set
    @Volatile var analyzerCompletedFps: Float = 0f; private set
    @Volatile var droppedOrSkippedTotal: Long = 0L; private set

    val isPoseBusy: Boolean get() = poseClient?.isBusy == true
    val isFaceBusy: Boolean get() = faceClient?.isBusy == true

    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }
        val captureTs = SystemClock.uptimeMillis()
        rollFps(captureTs)
        onCameraFrame?.invoke(captureTs)

        when (mode) {
            VisualAnalysisMode.BODY_ONLY -> routePose(imageProxy, captureTs)
            VisualAnalysisMode.FACE_ONLY -> routeFace(imageProxy, captureTs)
        }
    }

    fun onFaceTerminal(requestToken: Long, sid: Long, success: Boolean) {
        if (success) completedInWindow++
    }

    fun onPoseTerminal(requestToken: Long, sid: Long, success: Boolean) {
        if (success) completedInWindow++
    }

    fun close() {
        closed.set(true)
    }

    private fun routePose(imageProxy: ImageProxy, captureTs: Long) {
        val client = poseClient
        if (client == null || !client.isReady) {
            droppedOrSkippedTotal++
            imageProxy.close()
            return
        }
        if (!client.canAcceptFrame(captureTs, poseMinIntervalMs, sessionId)) {
            droppedOrSkippedTotal++
            imageProxy.close()
            return
        }
        client.detectLiveStream(
            imageProxy = imageProxy,
            minIntervalMs = poseMinIntervalMs,
            requestToken = captureTs,
            captureTimestampMs = captureTs,
        )
    }

    private fun routeFace(imageProxy: ImageProxy, captureTs: Long) {
        val client = faceClient
        if (client == null || !client.isReady) {
            droppedOrSkippedTotal++
            imageProxy.close()
            return
        }
        if (!client.canAcceptFrame(captureTs, faceMinIntervalMs, sessionId)) {
            droppedOrSkippedTotal++
            imageProxy.close()
            return
        }
        client.detectLiveStream(
            imageProxy = imageProxy,
            minIntervalMs = faceMinIntervalMs,
            requestToken = captureTs,
            captureTimestampMs = captureTs,
        )
    }

    private fun rollFps(nowMs: Long) {
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
            return
        }
        framesInWindow++
        val elapsed = nowMs - windowStartMs
        if (elapsed >= 1_000L) {
            val seconds = elapsed / 1000f
            cameraInputFps = framesInWindow / seconds
            analyzerCompletedFps = completedInWindow / seconds
            framesInWindow = 0
            completedInWindow = 0
            windowStartMs = nowMs
        }
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
            onFaceTerminal: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
            onPoseTerminal: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
        ): Pair<PoseLandmarkerClient?, FaceLandmarkerClient?> {
            require(mode == VisualAnalysisMode.BODY_ONLY || mode == VisualAnalysisMode.FACE_ONLY) {
                "Only BODY_ONLY or FACE_ONLY are supported"
            }
            // Never initialize Pose and Face together.
            val pose = if (mode == VisualAnalysisMode.BODY_ONLY) {
                PoseLandmarkerClient(
                    context = context,
                    onResult = onPoseFrame,
                    onError = onPoseError,
                    sessionIdProvider = { sessionId },
                    onTerminal = onPoseTerminal,
                ).also { it.initialize(); it.beginSession(sessionId) }
            } else {
                null
            }
            val face = if (mode == VisualAnalysisMode.FACE_ONLY) {
                FaceLandmarkerClient(
                    context = context,
                    onResult = onFaceFrame,
                    onError = onFaceError,
                    sessionIdProvider = { sessionId },
                    onTerminal = onFaceTerminal,
                ).also { it.initialize(); it.beginSession(sessionId) }
            } else {
                null
            }
            Log.d(TAG, "Initialized mode=$mode pose=${pose != null} face=${face != null}")
            check(!(pose != null && face != null)) {
                "Pose and Face must never be initialized simultaneously"
            }
            return pose to face
        }
    }
}
