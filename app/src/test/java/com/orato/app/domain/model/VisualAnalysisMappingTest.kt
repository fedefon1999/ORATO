package com.orato.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAnalysisMappingTest {

    @Test
    fun exactlyThreeScenariosAreSelectable() {
        assertEquals(3, Scenario.entries.size)
        assertEquals(
            listOf(Scenario.PRESENTATION, Scenario.EXAM, Scenario.CONVERSATION),
            Scenario.entries.toList(),
        )
    }

    @Test
    fun presentation_mapsToBodyOnly() {
        assertEquals(VisualAnalysisMode.BODY_ONLY, VisualAnalysisMapping.modeFor(Scenario.PRESENTATION))
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
    fun exam_displayLabel() {
        assertEquals("Esame universitario", Scenario.EXAM.displayName)
    }

    @Test
    fun videoCall_displayLabel() {
        assertEquals("Videochiamata online", Scenario.CONVERSATION.displayName)
    }

    @Test
    fun noSelectableScenarioDisplaysColloquio() {
        assertFalse(Scenario.entries.any { it.displayName.contains("Colloquio", ignoreCase = true) })
        assertFalse(Scenario.entries.any { it.description.contains("Colloquio", ignoreCase = true) })
        assertFalse(Scenario.entries.any { it.routeArg.equals("interview", ignoreCase = true) })
    }

    @Test
    fun noLegacyConversationLabelRemains() {
        assertFalse(Scenario.entries.any { it.displayName == "Conversazione" })
    }

    @Test
    fun visualAnalysisMode_hasNoBodyAndFace() {
        assertEquals(2, VisualAnalysisMode.entries.size)
        assertEquals(
            setOf(VisualAnalysisMode.BODY_ONLY, VisualAnalysisMode.FACE_ONLY),
            VisualAnalysisMode.entries.toSet(),
        )
    }

    @Test
    fun mappingDoesNotDependOnLocalizedStrings() {
        val presentation = Scenario.entries.first { it == Scenario.PRESENTATION }
        assertEquals(VisualAnalysisMode.BODY_ONLY, VisualAnalysisMapping.modeFor(presentation))
        assertTrue(Scenario.CONVERSATION.displayName != "Conversazione")
        assertEquals(VisualAnalysisMode.FACE_ONLY, VisualAnalysisMapping.modeFor(Scenario.CONVERSATION))
    }

    @Test
    fun calibrationSkippedForPresentation() {
        assertFalse(VisualAnalysisMapping.requiresFaceCalibration(Scenario.PRESENTATION))
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
    fun usesPoseAndFaceFlags_areMutuallyExclusive() {
        assertTrue(VisualAnalysisMapping.usesPose(VisualAnalysisMode.BODY_ONLY))
        assertFalse(VisualAnalysisMapping.usesFace(VisualAnalysisMode.BODY_ONLY))
        assertFalse(VisualAnalysisMapping.usesPose(VisualAnalysisMode.FACE_ONLY))
        assertTrue(VisualAnalysisMapping.usesFace(VisualAnalysisMode.FACE_ONLY))
    }

    @Test
    fun conversationRouteArgPreserved() {
        assertEquals("conversation", Scenario.CONVERSATION.routeArg)
        assertEquals(Scenario.CONVERSATION, Scenario.fromRouteArg("conversation"))
    }

    @Test
    fun legacyInterviewRoute_fallsBackToPresentation_andIsNotSelectable() {
        assertEquals(Scenario.PRESENTATION, Scenario.fromRouteArg("interview"))
        assertTrue(Scenario.entries.none { it.routeArg == "interview" })
    }
}
