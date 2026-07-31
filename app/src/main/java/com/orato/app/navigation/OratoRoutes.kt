package com.orato.app.navigation

object OratoRoutes {
    const val HOME = "home"
    const val SCENARIO_SELECTION = "scenario_selection"
    const val PRACTICE = "practice/{scenario}"

    fun practice(scenarioArg: String): String = "practice/$scenarioArg"
}
