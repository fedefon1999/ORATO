package com.orato.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.orato.app.domain.model.Scenario
import com.orato.app.ui.home.HomeScreen
import com.orato.app.ui.practice.PracticeScreen
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
            )
        }
    }
}
