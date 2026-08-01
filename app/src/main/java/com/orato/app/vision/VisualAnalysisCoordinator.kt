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
 * Shared CameraX frame router.
 *
 * One ImageAnalysis stream, STRATEGY_KEEP_ONLY_LATEST, no frame queues.
 * Routes each CameraX frame to **at most one** MediaPipe analyzer (no concurrent
 * ImageProxy ownership). Fair BODY_AND_FACE selection via [VisualFrameScheduler].
 *
 * ImageProxy is closed exactly once — either by the selected client or here when NONE.
 */
class VisualAnalysisCoordinator(
    private val mode: VisualAnalysisMode,
    private val poseClient: PoseLandmarkerClient?,
    private val faceClient: FaceLandmarkerClient?,
    private val sessionId: Long,
    private val scheduler: VisualFrameScheduler = VisualFrameScheduler(mode),
    private val onCameraFrame: ((captureTimestampMs: Long) -> Unit)? = null,
    private val faceMinIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
    private val poseMinIntervalMs: Long = FaceMetricsConfig.POSE_MIN_INTERVAL_MS,
) : ImageAnalysis.Analyzer {

    private val closed = AtomicBoolean(false)
    private val cameraFrames = AtomicLong(0L)
    private var windowStartMs = 0L
    private var framesInWindow = 0
    private var faceCompletedInWindow = 0
    private var poseCompletedInWindow = 0

    @Volatile var cameraInputFps: Float = 0f; private set
    @Volatile var faceCompletedFps: Float = 0f; private set
    @Volatile var poseCompletedFps: Float = 0f; private set
    @Volatile var droppedOrSkippedTotal: Long = 0L; private set

    val diagnosticsScheduler: VisualFrameScheduler get() = scheduler

    init {
        scheduler.beginSession(sessionId)
    }

    val isPoseBusy: Boolean get() = poseClient?.isBusy == true || scheduler.isPoseInFlight
    val isFaceBusy: Boolean get() = faceClient?.isBusy == true || scheduler.isFaceInFlight

    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }
        val captureTs = SystemClock.uptimeMillis()
        cameraFrames.incrementAndGet()
        rollFps(captureTs)
        onCameraFrame?.invoke(captureTs)

        val faceAvailable = faceClient?.isReady == true
        val poseAvailable = poseClient?.isReady == true

        val (selected, token) = scheduler.select(
            timestampMs = captureTs,
            faceAvailable = faceAvailable,
            poseAvailable = poseAvailable,
            sessionId = sessionId,
        )

        when (selected) {
            SelectedVisualAnalyzer.FACE -> {
                val client = faceClient
                if (client == null) {
                    scheduler.onFaceError(token, sessionId)
                    droppedOrSkippedTotal++
                    imageProxy.close()
                    return
                }
                client.detectLiveStream(
                    imageProxy = imageProxy,
                    minIntervalMs = faceMinIntervalMs,
                    requestToken = token,
                    captureTimestampMs = captureTs,
                )
            }
            SelectedVisualAnalyzer.POSE -> {
                val client = poseClient
                if (client == null) {
                    scheduler.onPoseError(token, sessionId)
                    droppedOrSkippedTotal++
                    imageProxy.close()
                    return
                }
                client.detectLiveStream(
                    imageProxy = imageProxy,
                    minIntervalMs = poseMinIntervalMs,
                    requestToken = token,
                    captureTimestampMs = captureTs,
                )
            }
            SelectedVisualAnalyzer.NONE -> {
                droppedOrSkippedTotal++
                imageProxy.close()
            }
        }
    }

    fun onFaceTerminal(requestToken: Long, sid: Long, success: Boolean) {
        if (success) {
            faceCompletedInWindow++
            scheduler.onFaceCompleted(requestToken, sid)
        } else {
            scheduler.onFaceError(requestToken, sid)
        }
    }

    fun onPoseTerminal(requestToken: Long, sid: Long, success: Boolean) {
        if (success) {
            poseCompletedInWindow++
            scheduler.onPoseCompleted(requestToken, sid)
        } else {
            scheduler.onPoseError(requestToken, sid)
        }
    }

    fun close() {
        closed.set(true)
        scheduler.close()
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
            faceCompletedFps = faceCompletedInWindow / seconds
            poseCompletedFps = poseCompletedInWindow / seconds
            framesInWindow = 0
            faceCompletedInWindow = 0
            poseCompletedInWindow = 0
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
            val pose = if (mode == VisualAnalysisMode.BODY_ONLY || mode == VisualAnalysisMode.BODY_AND_FACE) {
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
            val face = if (mode == VisualAnalysisMode.FACE_ONLY || mode == VisualAnalysisMode.BODY_AND_FACE) {
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
            return pose to face
        }
    }
}
