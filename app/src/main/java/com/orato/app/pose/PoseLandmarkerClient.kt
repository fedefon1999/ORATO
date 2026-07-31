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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaPipe Pose Landmarker wrapper for LIVE_STREAM camera frames.
 *
 * Create and [close] off the main thread when using CPU (same executor as ImageAnalysis).
 * Stale results after [close] are ignored.
 */
class PoseLandmarkerClient(
    context: Context,
    private val onResult: (UpperBodyPoseFrame) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private var poseLandmarker: PoseLandmarker? = null

    val isReady: Boolean
        get() = poseLandmarker != null && !closed.get()

    fun initialize() {
        if (closed.get()) return
        try {
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

    /**
     * Consumes [imageProxy] exactly once (always closed).
     * Rotates the frame to upright orientation before inference.
     * Front-camera mirroring is applied in [PoseOverlayMapper], not here.
     */
    fun detectLiveStream(imageProxy: ImageProxy) {
        if (closed.get() || poseLandmarker == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val frameTime = SystemClock.uptimeMillis()
        val width = imageProxy.width
        val height = imageProxy.height

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
            return
        }

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        try {
            poseLandmarker?.detectAsync(mpImage, frameTime)
        } catch (error: Exception) {
            Log.e(TAG, "detectAsync failed", error)
            if (!closed.get()) {
                onError("Errore durante l'analisi del fotogramma.")
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { poseLandmarker?.close() }
        poseLandmarker = null
    }

    private fun onLivestreamResult(result: PoseLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        if (closed.get()) return

        val poseLandmarks = result.landmarks().firstOrNull()
        if (poseLandmarks == null) {
            onResult(
                UpperBodyPoseFrame(
                    landmarks = emptyMap(),
                    imageWidth = input.width,
                    imageHeight = input.height,
                ),
            )
            return
        }

        val mapped = PoseLandmarkId.entries.mapNotNull { id ->
            val landmark = poseLandmarks.getOrNull(id.mediapipeIndex) ?: return@mapNotNull null
            id to NormalizedLandmarkPoint(
                id = id,
                x = landmark.x(),
                y = landmark.y(),
                visibility = landmark.visibility().orElse(0f),
            )
        }.toMap()

        onResult(
            UpperBodyPoseFrame(
                landmarks = mapped,
                imageWidth = input.width,
                imageHeight = input.height,
            ),
        )
    }

    companion object {
        private const val TAG = "PoseLandmarkerClient"
        private const val MODEL_ASSET = "pose_landmarker_lite.task"
        private const val DEFAULT_CONFIDENCE = 0.5f
    }
}
