package com.orato.app.navigation

object OratoRoutes {
    const val HOME = "home"
    const val SCENARIO_SELECTION = "scenario_selection"
    const val PRACTICE = "practice/{scenario}"
    const val REPORT_PREPARATION = "report_preparation/{scenario}/{sessionId}"
    const val FINAL_REPORT = "final_report/{scenario}"

    /** @deprecated Use [FINAL_REPORT]. Kept so old deep links do not crash. */
    const val BODY_REPORT = "body_report/{scenario}"

    fun practice(scenarioArg: String): String = "practice/$scenarioArg"

    fun reportPreparation(scenarioArg: String, sessionId: String): String =
        "report_preparation/$scenarioArg/$sessionId"

    fun finalReport(scenarioArg: String): String = "final_report/$scenarioArg"

    fun bodyReport(scenarioArg: String): String = "body_report/$scenarioArg"
}
