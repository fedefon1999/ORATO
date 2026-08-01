package com.orato.app.face

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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-flight Face inference request. Capture timestamp drives metrics;
 * submission/callback timestamps are diagnostics only.
 */
data class FaceInferenceRequest(
    val requestToken: Long,
    val sessionId: Long,
    val captureTimestampMs: Long,
    val submissionTimestampMs: Long,
)

/**
 * MediaPipe Face Landmarker wrapper for LIVE_STREAM camera frames.
 *
 * Busy remains true from successful submit until the matching result/error/close.
 * [FaceFrame.timestampMs] is the capture timestamp submitted to MediaPipe.
 */
class FaceLandmarkerClient(
    context: Context,
    private val onResult: (FaceFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val sessionIdProvider: () -> Long = { 0L },
    private val onTerminal: ((requestToken: Long, sessionId: Long, success: Boolean) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val lastSubmittedCaptureMs = AtomicLong(0L)
    private val activeSessionId = AtomicLong(0L)
    private val inFlight = AtomicReference<FaceInferenceRequest?>(null)
    private var faceLandmarker: FaceLandmarker? = null

    @Volatile var skippedWhileBusy: Long = 0L; private set
    @Volatile var skippedByThrottle: Long = 0L; private set
    @Volatile var staleCallbackCount: Long = 0L; private set
    @Volatile var duplicateCallbackCount: Long = 0L; private set
    @Volatile var lastInferenceDurationMs: Long = 0L; private set
    @Volatile var acceptedResults: Long = 0L; private set
    @Volatile var lastCaptureTimestampMs: Long = 0L; private set
    @Volatile var lastCallbackTimestampMs: Long = 0L; private set

    val isReady: Boolean
        get() = faceLandmarker != null && !closed.get()

    val isBusy: Boolean
        get() = busy.get()

    fun initialize() {
        if (closed.get()) return
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .setResultListener(::onLivestreamResult)
                .setErrorListener { error ->
                    releaseInFlight(success = false)
                    if (!closed.get()) {
                        onError(error.message ?: "Errore durante il rilevamento del viso.")
                    }
                }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(appContext, options)
            activeSessionId.set(sessionIdProvider())
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load Face Landmarker model", error)
            faceLandmarker = null
            if (!closed.get()) {
                onError(
                    "Impossibile caricare il modello di rilevamento del viso. " +
                        "Riprova o reinstalla l'app.",
                )
            }
        }
    }

    fun beginSession(sessionId: Long) {
        activeSessionId.set(sessionId)
        skippedWhileBusy = 0L
        skippedByThrottle = 0L
        staleCallbackCount = 0L
        duplicateCallbackCount = 0L
        acceptedResults = 0L
        lastSubmittedCaptureMs.set(0L)
        inFlight.set(null)
        busy.set(false)
    }

    /**
     * Eligibility peek — must match [detectLiveStream] rejection rules.
     */
    fun canAcceptFrame(
        captureTimestampMs: Long,
        minIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
        sessionId: Long = sessionIdProvider(),
    ): Boolean {
        if (closed.get() || faceLandmarker == null) return false
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
     * @param requestToken optional external token from [com.orato.app.vision.VisualFrameScheduler]
     * @param captureTimestampMs monotonic capture time; defaults to uptime when omitted
     */
    fun detectLiveStream(
        imageProxy: ImageProxy,
        minIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
        requestToken: Long = 0L,
        captureTimestampMs: Long = SystemClock.uptimeMillis(),
    ) {
        if (closed.get() || faceLandmarker == null) {
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

        val request = FaceInferenceRequest(
            requestToken = token,
            sessionId = submitSessionId,
            captureTimestampMs = captureTimestampMs,
            submissionTimestampMs = submissionMs,
        )
        inFlight.set(request)
        lastSubmittedCaptureMs.set(captureTimestampMs)
        lastCaptureTimestampMs = captureTimestampMs

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        try {
            // MediaPipe timestamp must be the capture timestamp.
            faceLandmarker?.detectAsync(mpImage, captureTimestampMs)
        } catch (error: Exception) {
            Log.e(TAG, "detectAsync failed", error)
            releaseInFlight(success = false)
            if (!closed.get()) {
                onError("Errore durante l'analisi del fotogramma del viso.")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { faceLandmarker?.close() }
        faceLandmarker = null
        val pending = inFlight.getAndSet(null)
        busy.set(false)
        if (pending != null) {
            onTerminal?.invoke(pending.requestToken, pending.sessionId, false)
        }
    }

    private fun onLivestreamResult(
        result: FaceLandmarkerResult,
        input: com.google.mediapipe.framework.image.MPImage,
    ) {
        val callbackMs = SystemClock.uptimeMillis()
        lastCallbackTimestampMs = callbackMs
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
            // Do not clear current session busy via stale callback if tokens diverge.
            if (inFlight.get()?.requestToken == pending.requestToken) {
                releaseInFlight(success = false)
            }
            return
        }

        lastInferenceDurationMs = (callbackMs - pending.submissionTimestampMs).coerceAtLeast(0L)
        acceptedResults++
        val frame = FaceFrameMapper.map(
            result = result,
            timestampMs = pending.captureTimestampMs,
            imageWidth = input.width,
            imageHeight = input.height,
        )
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
        private const val TAG = "FaceLandmarkerClient"
        const val MODEL_ASSET = "face_landmarker.task"
    }
}
