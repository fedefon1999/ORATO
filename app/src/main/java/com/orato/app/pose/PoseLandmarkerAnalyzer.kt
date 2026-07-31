package com.orato.app.pose

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX [ImageAnalysis.Analyzer] that forwards RGBA frames to [PoseLandmarkerClient].
 * Runs on the analysis executor (never the main thread).
 */
class PoseLandmarkerAnalyzer(
    private val client: PoseLandmarkerClient,
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        client.detectLiveStream(imageProxy)
    }
}
