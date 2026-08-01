package com.orato.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAnalysisMappingTest {

    @Test
    fun presentation_mapsToBodyOnly() {
        assertEquals(VisualAnalysisMode.BODY_ONLY, VisualAnalysisMapping.modeFor(Scenario.PRESENTATION))
    }

    @Test
    fun interview_mapsToBodyAndFace() {
        assertEquals(VisualAnalysisMode.BODY_AND_FACE, VisualAnalysisMapping.modeFor(Scenario.INTERVIEW))
    }

    @Test
    fun universityExam_mapsToFaceOnly() {
        assertEquals(VisualAnalysisMode.FACE_ONLY, VisualAnalysisMapping.modeFor(Scenario.EXAM))
    }

    @Test
    fun videoCall_mapsToFaceOnly() {
        assertEquals(VisualAnalysisMode.FACE_ONLY, VisualAnalysisMapping.modeFor(Scenario.CONVERSATION))
    }

    @Test
    fun presentation_displayLabel() {
        assertEquals("Presentazione davanti al pubblico", Scenario.PRESENTATION.displayName)
    }

    @Test
    fun videoCall_displayLabel() {
        assertEquals("Videochiamata online", Scenario.CONVERSATION.displayName)
    }

    @Test
    fun noLegacyConversationLabelRemains() {
        assertFalse(Scenario.entries.any { it.displayName == "Conversazione" })
    }

    @Test
    fun mappingDoesNotDependOnLocalizedStrings() {
        // Remap using enum identity only — labels must not affect mode.
        val presentation = Scenario.entries.first { it == Scenario.PRESENTATION }
        val renamedWouldStillMap = VisualAnalysisMapping.modeFor(presentation)
        assertEquals(VisualAnalysisMode.BODY_ONLY, renamedWouldStillMap)
        assertTrue(Scenario.CONVERSATION.displayName != "Conversazione")
        assertEquals(VisualAnalysisMode.FACE_ONLY, VisualAnalysisMapping.modeFor(Scenario.CONVERSATION))
    }

    @Test
    fun calibrationSkippedForPresentation() {
        assertFalse(VisualAnalysisMapping.requiresFaceCalibration(Scenario.PRESENTATION))
    }

    @Test
    fun calibrationRunsForInterview() {
        assertTrue(VisualAnalysisMapping.requiresFaceCalibration(Scenario.INTERVIEW))
    }

    @Test
    fun calibrationRunsForExam() {
        assertTrue(VisualAnalysisMapping.requiresFaceCalibration(Scenario.EXAM))
    }

    @Test
    fun calibrationRunsForVideoCall() {
        assertTrue(VisualAnalysisMapping.requiresFaceCalibration(Scenario.CONVERSATION))
    }

    @Test
    fun usesPoseAndFaceFlags() {
        assertTrue(VisualAnalysisMapping.usesPose(VisualAnalysisMode.BODY_ONLY))
        assertFalse(VisualAnalysisMapping.usesFace(VisualAnalysisMode.BODY_ONLY))
        assertFalse(VisualAnalysisMapping.usesPose(VisualAnalysisMode.FACE_ONLY))
        assertTrue(VisualAnalysisMapping.usesFace(VisualAnalysisMode.FACE_ONLY))
        assertTrue(VisualAnalysisMapping.usesPose(VisualAnalysisMode.BODY_AND_FACE))
        assertTrue(VisualAnalysisMapping.usesFace(VisualAnalysisMode.BODY_AND_FACE))
    }

    @Test
    fun conversationRouteArgPreserved() {
        assertEquals("conversation", Scenario.CONVERSATION.routeArg)
        assertEquals(Scenario.CONVERSATION, Scenario.fromRouteArg("conversation"))
    }
}
