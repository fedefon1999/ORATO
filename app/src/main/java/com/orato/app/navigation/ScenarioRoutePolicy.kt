package com.orato.app.navigation

import com.orato.app.domain.model.Scenario

/**
 * Pure routing policy for scenario deep links / route arguments.
 * Unknown or removed args never start a session; recovery is scenario selection.
 */
object ScenarioRoutePolicy {
    fun canStartPracticeSession(routeArg: String): Boolean =
        Scenario.fromRouteArg(routeArg) != null

    fun recoveryDestinationFor(routeArg: String): String {
        require(!canStartPracticeSession(routeArg)) {
            "Known scenario does not need recovery: $routeArg"
        }
        return OratoRoutes.destinationForUnknownScenario()
    }
}
