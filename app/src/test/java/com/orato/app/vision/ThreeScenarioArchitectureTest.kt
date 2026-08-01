package com.orato.app.vision

import com.orato.app.domain.model.Scenario
import com.orato.app.domain.model.VisualAnalysisMapping
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.FaceMetricsConfig
import com.orato.app.ui.practice.practiceRunningInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture invariants for the three-scenario Face Coach product.
 * Does not require camera hardware, MediaPipe models, Whisper, or network.
 */
class ThreeScenarioArchitectureTest {

    @Test
    fun bodyOnly_instruction_isBodyFramingOnly() {
        val text = practiceRunningInstruction(VisualAnalysisMode.BODY_ONLY)
        assertTrue(text.contains("corpo", ignoreCase = true))
        assertFalse(text.contains("sguardo", ignoreCase = true))
        assertFalse(text.contains("spalle", ignoreCase = true))
        assertFalse(text.contains("viso", ignoreCase = true))
    }

    @Test
    fun faceOnly_instruction_isFaceAndGaze() {
        val text = practiceRunningInstruction(VisualAnalysisMode.FACE_ONLY)
        assertTrue(text.contains("viso", ignoreCase = true))
        assertTrue(text.contains("videocamera", ignoreCase = true))
        assertFalse(text.contains("spalle", ignoreCase = true))
        assertFalse(text.contains("busto", ignoreCase = true))
    }

    @Test
    fun poseAndFaceNeverBothUsedByMapping() {
        for (scenario in Scenario.entries) {
            val mode = VisualAnalysisMapping.modeFor(scenario)
            val pose = VisualAnalysisMapping.usesPose(mode)
            val face = VisualAnalysisMapping.usesFace(mode)
            assertTrue(pose xor face)
        }
    }

    @Test
    fun combinedSchedulerClassRemoved() {
        assertNull(
            runCatching { Class.forName("com.orato.app.vision.VisualFrameScheduler") }.getOrNull(),
        )
        assertNull(
            runCatching { Class.forName("com.orato.app.vision.SelectedVisualAnalyzer") }.getOrNull(),
        )
    }

    @Test
    fun upperBodyCalibrationEvidenceRemoved() {
        assertNull(
            runCatching { Class.forName("com.orato.app.face.UpperBodyCalibrationEvidence") }.getOrNull(),
        )
        val configFields = FaceMetricsConfig::class.java.declaredFields.map { it.name }
        assertFalse(configFields.any { it.contains("UPPER_BODY", ignoreCase = true) })
        assertFalse(configFields.any { it.contains("INTERVIEW", ignoreCase = true) })
    }

    @Test
    fun faceCalibrationStillRequiresThreeContinuousSeconds() {
        assertEquals(3_000L, FaceMetricsConfig.MIN_VALID_CALIBRATION_DURATION_MS)
    }

    @Test
    fun reportSections_bodyOnlyNeverWaitsForFaceSemantics() {
        assertTrue(VisualAnalysisMapping.usesPose(VisualAnalysisMode.BODY_ONLY))
        assertFalse(VisualAnalysisMapping.usesFace(VisualAnalysisMode.BODY_ONLY))
    }

    @Test
    fun reportSections_faceOnlyNeverWaitsForPoseSemantics() {
        assertTrue(VisualAnalysisMapping.usesFace(VisualAnalysisMode.FACE_ONLY))
        assertFalse(VisualAnalysisMapping.usesPose(VisualAnalysisMode.FACE_ONLY))
    }
}
