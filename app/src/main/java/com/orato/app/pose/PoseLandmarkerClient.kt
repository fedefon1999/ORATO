package com.orato.app.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.orato.app.face.FaceMetricsConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PoseInferenceRequest(
    val requestToken: Long,
    val sessionId: Long,
    val captureTimestampMs: Long,
    val submissionTimestampMs: Long,
)

/**
 * MediaPipe Pose Landmarker wrapper for LIVE_STREAM camera frames.
 *
 * Busy remains true from successful submit until the matching result/error/close.
 * Frame [UpperBodyPoseFrame.timestampMs] is the capture timestamp submitted to MediaPipe.
 */
class PoseLandmarkerClient(
    context: Context,
    private val onResult: (UpperBodyPoseFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val sessionIdProvider: () -> Long = { 0L },
    private val onTerminal: ((requestToken: Long, sessionId: Long, success: Boolean) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val lastSubmittedCaptureMs = AtomicLong(0L)
    private val activeSessionId = AtomicLong(0L)
    private val inFlight = AtomicReference<PoseInferenceRequest?>(null)
    private var poseLandmarker: PoseLandmarker? = null

    @Volatile var skippedWhileBusy: Long = 0L; private set
    @Volatile var skippedByThrottle: Long = 0L; private set
    @Volatile var staleCallbackCount: Long = 0L; private set
    @Volatile var duplicateCallbackCount: Long = 0L; private set
    @Volatile var lastInferenceDurationMs: Long = 0L; private set
    @Volatile var acceptedResults: Long = 0L; private set

    val isReady: Boolean
        get() = poseLandmarker != null && !closed.get()

    val isBusy: Boolean
        get() = busy.get()

    fun beginSession(sessionId: Long) {
        activeSessionId.set(sessionId)
        staleCallbackCount = 0L
        skippedWhileBusy = 0L
        skippedByThrottle = 0L
        duplicateCallbackCount = 0L
        acceptedResults = 0L
        lastSubmittedCaptureMs.set(0L)
        inFlight.set(null)
        busy.set(false)
    }

    fun initialize() {
        if (closed.get()) return
        try {
            activeSessionId.set(sessionIdProvider())
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(DEFAULT_CONFIDENCE)
                .setMinTrackingConfidence(DEFAULT_CONFIDENCE)
                .setMinPosePresenceConfidence(DEFAULT_CONFIDENCE)
                .setResultListener(::onLivestreamResult)
                .setErrorListener { error ->
                    releaseInFlight(success = false)
                    if (!closed.get()) {
                        onError(error.message ?: "Errore durante il rilevamento della posa.")
                    }
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(appContext, options)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load Pose Landmarker model", error)
            poseLandmarker = null
            if (!closed.get()) {
                onError(
                    "Impossibile caricare il modello di rilevamento posa. " +
                        "Riprova o reinstalla l'app.",
                )
            }
        }
    }

    fun canAcceptFrame(
        captureTimestampMs: Long,
        minIntervalMs: Long = FaceMetricsConfig.POSE_MIN_INTERVAL_MS,
        sessionId: Long = sessionIdProvider(),
    ): Boolean {
        if (closed.get() || poseLandmarker == null) return false
        if (sessionId != activeSessionId.get()) return false
        if (busy.get()) return false
        val last = lastSubmittedCaptureMs.get()
        if (last > 0L) {
            if (captureTimestampMs == last) return false
            if (captureTimestampMs < last) return false
            if (captureTimestampMs - last < minIntervalMs) return false
        }
        return true
    }

    /**
     * Consumes [imageProxy] exactly once (always closed).
     */
    fun detectLiveStream(
        imageProxy: ImageProxy,
        minIntervalMs: Long = FaceMetricsConfig.POSE_MIN_INTERVAL_MS,
        requestToken: Long = 0L,
        captureTimestampMs: Long = SystemClock.uptimeMillis(),
    ) {
        if (closed.get() || poseLandmarker == null) {
            imageProxy.close()
            return
        }

        if (!canAcceptFrame(captureTimestampMs, minIntervalMs)) {
            if (busy.get()) skippedWhileBusy++ else skippedByThrottle++
            imageProxy.close()
            if (requestToken != 0L) {
                onTerminal?.invoke(requestToken, sessionIdProvider(), false)
            }
            return
        }

        if (!busy.compareAndSet(false, true)) {
            skippedWhileBusy++
            imageProxy.close()
            if (requestToken != 0L) {
                onTerminal?.invoke(requestToken, sessionIdProvider(), false)
            }
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height
        val submitSessionId = sessionIdProvider()
        val submissionMs = SystemClock.uptimeMillis()
        val token = if (requestToken != 0L) requestToken else submissionMs

        val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        } finally {
            imageProxy.close()
        }

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true,
        )
        if (rotatedBitmap !== bitmapBuffer) bitmapBuffer.recycle()

        if (closed.get()) {
            rotatedBitmap.recycle()
            busy.set(false)
            return
        }

        val request = PoseInferenceRequest(
            requestToken = token,
            sessionId = submitSessionId,
            captureTimestampMs = captureTimestampMs,
            submissionTimestampMs = submissionMs,
        )
        inFlight.set(request)
        lastSubmittedCaptureMs.set(captureTimestampMs)

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        try {
            poseLandmarker?.detectAsync(mpImage, captureTimestampMs)
        } catch (error: Exception) {
            Log.e(TAG, "detectAsync failed", error)
            releaseInFlight(success = false)
            if (!closed.get()) {
                onError("Errore durante l'analisi del fotogramma.")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { poseLandmarker?.close() }
        poseLandmarker = null
        val pending = inFlight.getAndSet(null)
        busy.set(false)
        if (pending != null) {
            onTerminal?.invoke(pending.requestToken, pending.sessionId, false)
        }
    }

    private fun onLivestreamResult(
        result: PoseLandmarkerResult,
        input: com.google.mediapipe.framework.image.MPImage,
    ) {
        val callbackMs = SystemClock.uptimeMillis()
        val pending = inFlight.get()
        if (pending == null) {
            duplicateCallbackCount++
            return
        }
        if (closed.get()) {
            staleCallbackCount++
            releaseInFlight(success = false)
            return
        }
        if (pending.sessionId != activeSessionId.get() ||
            pending.sessionId != sessionIdProvider()
        ) {
            staleCallbackCount++
            if (inFlight.get()?.requestToken == pending.requestToken) {
                releaseInFlight(success = false)
            }
            return
        }

        lastInferenceDurationMs = (callbackMs - pending.submissionTimestampMs).coerceAtLeast(0L)
        acceptedResults++
        val captureTs = pending.captureTimestampMs

        val poseLandmarks = result.landmarks().firstOrNull()
        val frame = if (poseLandmarks == null) {
            UpperBodyPoseFrame(
                landmarks = emptyMap(),
                imageWidth = input.width,
                imageHeight = input.height,
                timestampMs = captureTs,
            )
        } else {
            val mapped = PoseLandmarkId.entries.mapNotNull { id ->
                val landmark = poseLandmarks.getOrNull(id.mediapipeIndex) ?: return@mapNotNull null
                val presenceOpt = landmark.presence()
                id to NormalizedLandmarkPoint(
                    id = id,
                    x = landmark.x(),
                    y = landmark.y(),
                    visibility = landmark.visibility().orElse(0f),
                    presence = if (presenceOpt.isPresent) presenceOpt.get() else null,
                )
            }.toMap()
            UpperBodyPoseFrame(
                landmarks = mapped,
                imageWidth = input.width,
                imageHeight = input.height,
                timestampMs = captureTs,
            )
        }
        releaseInFlight(success = true)
        onResult(frame)
    }

    private fun releaseInFlight(success: Boolean) {
        val pending = inFlight.getAndSet(null)
        busy.set(false)
        if (pending != null) {
            onTerminal?.invoke(pending.requestToken, pending.sessionId, success)
        }
    }

    companion object {
        private const val TAG = "PoseLandmarkerClient"
        private const val MODEL_ASSET = "pose_landmarker_lite.task"
        private const val DEFAULT_CONFIDENCE = 0.5f
    }
}
