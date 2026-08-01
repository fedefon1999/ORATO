package com.orato.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.PendingSessionReport
import com.orato.app.audio.SessionPracticeReport
import com.orato.app.domain.model.Scenario
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.SpeechSessionResult
import com.orato.app.ui.home.HomeScreen
import com.orato.app.ui.practice.PracticeScreen
import com.orato.app.ui.report.BodyReportScreen
import com.orato.app.ui.scenario.ScenarioSelectionScreen

@Composable
fun OratoNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = OratoRoutes.HOME,
        modifier = modifier,
    ) {
        composable(OratoRoutes.HOME) {
            HomeScreen(
                onStartPractice = {
                    navController.navigate(OratoRoutes.SCENARIO_SELECTION)
                },
            )
        }

        composable(OratoRoutes.SCENARIO_SELECTION) {
            ScenarioSelectionScreen(
                onScenarioSelected = { scenario ->
                    navController.navigate(OratoRoutes.practice(scenario.routeArg))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = OratoRoutes.PRACTICE,
            arguments = listOf(
                navArgument("scenario") { type = NavType.StringType },
            ),
        ) { entry ->
            val scenarioArg = entry.arguments?.getString("scenario").orEmpty()
            val scenario = Scenario.fromRouteArg(scenarioArg)
            PracticeScreen(
                scenario = scenario,
                onExit = { navController.popBackStack() },
                onSessionComplete = { report ->
                    PendingSessionReport.set(report)
                    navController.navigate(OratoRoutes.bodyReport(scenario.routeArg)) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = OratoRoutes.BODY_REPORT,
            arguments = listOf(
                navArgument("scenario") { type = NavType.StringType },
            ),
        ) { entry ->
            val scenarioArg = entry.arguments?.getString("scenario").orEmpty()
            val scenario = Scenario.fromRouteArg(scenarioArg)
            val liveReport by PendingSessionReport.report.collectAsStateWithLifecycle()
            val report: SessionPracticeReport =
                liveReport
                    ?: SessionPracticeReport(
                        body = SessionBodyReport.emptyInsufficient(),
                        audio = AudioSessionMetrics.idle(),
                        speech = SpeechSessionResult.NotAttempted,
                    )

            BodyReportScreen(
                scenario = scenario,
                report = report,
                onHome = {
                    PendingSessionReport.clear()
                    navController.navigate(OratoRoutes.HOME) {
                        popUpTo(OratoRoutes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onRepeat = {
                    PendingSessionReport.clear()
                    navController.navigate(OratoRoutes.practice(scenario.routeArg)) {
                        popUpTo(OratoRoutes.practice(scenario.routeArg)) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
