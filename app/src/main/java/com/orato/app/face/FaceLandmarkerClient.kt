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
 * MediaPipe Face Landmarker wrapper for LIVE_STREAM camera frames.
 *
 * Create and [close] off the main thread when using CPU (same executor as ImageAnalysis).
 * Stale results after [close] or with a mismatched [sessionId] are ignored.
 */
class FaceLandmarkerClient(
    context: Context,
    private val onResult: (FaceFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val sessionIdProvider: () -> Long = { 0L },
) {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val lastAcceptedTimestampMs = AtomicLong(0L)
    private val activeSessionId = AtomicReference(0L)
    private var faceLandmarker: FaceLandmarker? = null

    @Volatile
    var skippedWhileBusy: Long = 0L
        private set

    @Volatile
    var skippedByThrottle: Long = 0L
        private set

    @Volatile
    var staleCallbackCount: Long = 0L
        private set

    @Volatile
    var lastInferenceDurationMs: Long = 0L
        private set

    @Volatile
    var acceptedResults: Long = 0L
        private set

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
        acceptedResults = 0L
        lastAcceptedTimestampMs.set(0L)
    }

    /**
     * Consumes [imageProxy] exactly once (always closed).
     * Applies in-flight busy protection and timestamp throttling.
     */
    fun detectLiveStream(
        imageProxy: ImageProxy,
        minIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
    ) {
        if (closed.get() || faceLandmarker == null) {
            imageProxy.close()
            return
        }

        val now = SystemClock.uptimeMillis()
        val last = lastAcceptedTimestampMs.get()
        if (last > 0L && now - last < minIntervalMs) {
            skippedByThrottle++
            imageProxy.close()
            return
        }

        if (!busy.compareAndSet(false, true)) {
            skippedWhileBusy++
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val frameTime = now
        val width = imageProxy.width
        val height = imageProxy.height
        val submitSessionId = sessionIdProvider()

        val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        } finally {
            imageProxy.close()
        }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true,
        )
        if (rotatedBitmap !== bitmapBuffer) {
            bitmapBuffer.recycle()
        }

        if (closed.get()) {
            rotatedBitmap.recycle()
            busy.set(false)
            return
        }

        inferenceStartedAtMs.set(SystemClock.uptimeMillis())
        pendingSessionId.set(submitSessionId)
        lastAcceptedTimestampMs.set(frameTime)

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        try {
            faceLandmarker?.detectAsync(mpImage, frameTime)
        } catch (error: Exception) {
            Log.e(TAG, "detectAsync failed", error)
            busy.set(false)
            if (!closed.get()) {
                onError("Errore durante l'analisi del fotogramma del viso.")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { faceLandmarker?.close() }
        faceLandmarker = null
        busy.set(false)
    }

    private val inferenceStartedAtMs = AtomicLong(0L)
    private val pendingSessionId = AtomicLong(0L)

    private fun onLivestreamResult(
        result: FaceLandmarkerResult,
        input: com.google.mediapipe.framework.image.MPImage,
    ) {
        val started = inferenceStartedAtMs.get()
        if (started > 0L) {
            lastInferenceDurationMs = SystemClock.uptimeMillis() - started
        }
        busy.set(false)

        if (closed.get()) {
            staleCallbackCount++
            return
        }
        val expected = activeSessionId.get()
        val pending = pendingSessionId.get()
        if (expected != pending || expected != sessionIdProvider()) {
            staleCallbackCount++
            return
        }

        acceptedResults++
        val frame = FaceFrameMapper.map(
            result = result,
            timestampMs = SystemClock.uptimeMillis(),
            imageWidth = input.width,
            imageHeight = input.height,
        )
        onResult(frame)
    }

    companion object {
        private const val TAG = "FaceLandmarkerClient"
        const val MODEL_ASSET = "face_landmarker.task"
    }
}
