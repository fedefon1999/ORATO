package com.orato.app.navigation

object OratoRoutes {
    const val HOME = "home"
    const val SCENARIO_SELECTION = "scenario_selection"
    const val PRACTICE = "practice/{scenario}"
    const val BODY_REPORT = "body_report/{scenario}"

    fun practice(scenarioArg: String): String = "practice/$scenarioArg"

    fun bodyReport(scenarioArg: String): String = "body_report/$scenarioArg"
}
