package com.orato.app.domain.model

import com.orato.app.navigation.OratoRoutes
import com.orato.app.navigation.ScenarioRoutePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioRouteResolutionTest {

    @Test
    fun fromRouteArg_interview_doesNotReturnPresentation() {
        val resolved = Scenario.fromRouteArg("interview")
        assertNull(resolved)
        assertNotEquals(Scenario.PRESENTATION, resolved)
    }

    @Test
    fun removedInterviewRoute_cannotStartPracticeSession() {
        assertFalse(ScenarioRoutePolicy.canStartPracticeSession("interview"))
        assertFalse(ScenarioRoutePolicy.canStartPracticeSession("Interview"))
        assertNull(Scenario.fromRouteArg("interview"))
    }

    @Test
    fun unknownRoute_safelyReturnsToScenarioSelection() {
        assertNull(Scenario.fromRouteArg("unknown-scenario"))
        assertNull(Scenario.fromRouteArg(""))
        assertEquals(
            OratoRoutes.SCENARIO_SELECTION,
            ScenarioRoutePolicy.recoveryDestinationFor("interview"),
        )
        assertEquals(
            OratoRoutes.SCENARIO_SELECTION,
            ScenarioRoutePolicy.recoveryDestinationFor("not-a-scenario"),
        )
        assertEquals(
            OratoRoutes.destinationForUnknownScenario(),
            OratoRoutes.SCENARIO_SELECTION,
        )
    }

    @Test
    fun exactlyThreeScenariosRemainSelectable() {
        assertEquals(3, Scenario.entries.size)
        assertEquals(
            setOf("presentation", "exam", "conversation"),
            Scenario.entries.map { it.routeArg }.toSet(),
        )
    }

    @Test
    fun existingScenarioRoutes_stillResolveCorrectly() {
        assertEquals(Scenario.PRESENTATION, Scenario.fromRouteArg("presentation"))
        assertEquals(Scenario.EXAM, Scenario.fromRouteArg("exam"))
        assertEquals(Scenario.CONVERSATION, Scenario.fromRouteArg("conversation"))
        assertTrue(ScenarioRoutePolicy.canStartPracticeSession("presentation"))
        assertTrue(ScenarioRoutePolicy.canStartPracticeSession("exam"))
        assertTrue(ScenarioRoutePolicy.canStartPracticeSession("conversation"))
        assertNotNull(Scenario.fromRouteArg("presentation"))
    }

    @Test
    fun noProductionScenarioEnumNamedInterview() {
        assertTrue(Scenario.entries.none { it.name.equals("INTERVIEW", ignoreCase = true) })
        assertTrue(Scenario.entries.none { it.routeArg.equals("interview", ignoreCase = true) })
    }
}
