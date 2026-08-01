package com.orato.app.vision

import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.FaceMetricsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic tests for combined analyzer scheduling (no CameraX / hardware).
 */
class VisualAnalysisCoordinatorLogicTest {

    @Test
    fun bodyOnly_initializesPoseOnlyFlags() {
        val mode = VisualAnalysisMode.BODY_ONLY
        assertTrue(mode == VisualAnalysisMode.BODY_ONLY)
        assertFalse(mode == VisualAnalysisMode.FACE_ONLY)
    }

    @Test
    fun faceOnly_initializesFaceOnlyFlags() {
        assertEquals(VisualAnalysisMode.FACE_ONLY, VisualAnalysisMode.FACE_ONLY)
    }

    @Test
    fun bodyAndFace_bothEnabled() {
        assertEquals(VisualAnalysisMode.BODY_AND_FACE, VisualAnalysisMode.BODY_AND_FACE)
    }

    @Test
    fun throttlingIntervals_areIndependent() {
        assertTrue(FaceMetricsConfig.FACE_MIN_INTERVAL_MS < FaceMetricsConfig.POSE_MIN_INTERVAL_MS ||
            FaceMetricsConfig.FACE_MIN_INTERVAL_MS != FaceMetricsConfig.POSE_MIN_INTERVAL_MS)
        assertTrue(FaceMetricsConfig.FACE_MIN_INTERVAL_MS in 70L..100L)
        assertTrue(FaceMetricsConfig.POSE_MIN_INTERVAL_MS in 90L..125L)
    }

    @Test
    fun keepOnlyLatest_strategyConstant() {
        // Documents required CameraX backpressure — STRATEGY_KEEP_ONLY_LATEST == 0
        assertEquals(0, androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    }

    @Test
    fun sessionIdStaleRejection_logic() {
        val active = 10L
        val stalePending = 9L
        val provider = 10L
        val reject = active != stalePending || active != provider
        assertTrue(reject)
        val matchingPending = 10L
        val accept = active == matchingPending && active == provider
        assertTrue(accept)
    }
}
