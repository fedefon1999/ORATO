package com.orato.app.face

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX [ImageAnalysis.Analyzer] that forwards RGBA frames to [FaceLandmarkerClient].
 * Busy/throttle protection lives in the client — no unbounded queue.
 */
class FaceFrameAnalyzer(
    private val client: FaceLandmarkerClient,
    private val minIntervalMs: Long = FaceMetricsConfig.FACE_MIN_INTERVAL_MS,
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        client.detectLiveStream(imageProxy, minIntervalMs)
    }
}
