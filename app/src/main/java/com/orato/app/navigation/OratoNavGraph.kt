package com.orato.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.orato.app.domain.model.Scenario
import com.orato.app.report.PendingCompletedReport
import com.orato.app.report.ReportPreparationCoordinator
import com.orato.app.ui.home.HomeScreen
import com.orato.app.ui.practice.PracticeScreen
import com.orato.app.ui.report.FinalReportScreen
import com.orato.app.ui.report.ReportPreparationScreen
import com.orato.app.ui.scenario.ScenarioSelectionScreen

@Composable
fun OratoNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val reportPrep = remember(appContext) {
        ReportPreparationCoordinator.get(appContext)
    }

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
                onSessionEnded = { nav ->
                    navController.navigate(
                        OratoRoutes.reportPreparation(scenario.routeArg, nav.sessionId),
                    ) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = OratoRoutes.REPORT_PREPARATION,
            arguments = listOf(
                navArgument("scenario") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
            ),
        ) { entry ->
            val scenarioArg = entry.arguments?.getString("scenario").orEmpty()
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val scenario = Scenario.fromRouteArg(scenarioArg)

            ReportPreparationScreen(
                sessionId = sessionId,
                onReady = {
                    navController.navigate(OratoRoutes.finalReport(scenario.routeArg)) {
                        popUpTo(OratoRoutes.REPORT_PREPARATION) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onCancelled = {
                    reportPrep.clear()
                    PendingCompletedReport.clear()
                    navController.popBackStack(OratoRoutes.practice(scenarioArg), inclusive = false)
                },
                onFailedHome = {
                    reportPrep.clear()
                    PendingCompletedReport.clear()
                    navController.navigate(OratoRoutes.HOME) {
                        popUpTo(OratoRoutes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = OratoRoutes.FINAL_REPORT,
            arguments = listOf(
                navArgument("scenario") { type = NavType.StringType },
            ),
        ) { entry ->
            val scenarioArg = entry.arguments?.getString("scenario").orEmpty()
            val scenario = Scenario.fromRouteArg(scenarioArg)
            val liveReport by PendingCompletedReport.report.collectAsStateWithLifecycle()
            val report = liveReport

            if (report == null) {
                LaunchedEffect(Unit) {
                    navController.popBackStack(OratoRoutes.HOME, inclusive = false)
                }
            } else {
                FinalReportScreen(
                    scenario = scenario,
                    report = report,
                    onHome = {
                        PendingCompletedReport.clear()
                        reportPrep.clear()
                        navController.navigate(OratoRoutes.HOME) {
                            popUpTo(OratoRoutes.HOME) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onRepeat = {
                        PendingCompletedReport.clear()
                        reportPrep.clear()
                        navController.navigate(OratoRoutes.practice(scenario.routeArg)) {
                            popUpTo(OratoRoutes.practice(scenario.routeArg)) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
