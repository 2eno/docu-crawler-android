package dev.docucrawler.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.docucrawler.android.ui.screens.DashboardScreen
import dev.docucrawler.android.ui.screens.ExportScreen
import dev.docucrawler.android.ui.screens.LoginScreen
import dev.docucrawler.android.ui.screens.SetupScreen

object Routes {
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val EXPORT = "export"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.SETUP, "Setup", Icons.Filled.Tune),
    Tab(Routes.DASHBOARD, "Crawler", Icons.Filled.Dashboard),
    Tab(Routes.EXPORT, "Export", Icons.Filled.Archive),
)

@Composable
fun DocuCrawlerNavHost(
    navController: NavHostController = rememberNavController(),
    vm: MainViewModel = viewModel(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    fun navigateTab(route: String) = navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        bottomBar = {
            if (currentRoute != Routes.LOGIN) {
                val crawl by vm.crawlState.collectAsStateWithLifecycle()
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = {
                                Text(if (tab.route == Routes.DASHBOARD && crawl.isActive) "${tab.label} •" else tab.label)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SETUP,
            modifier = if (currentRoute == Routes.LOGIN) Modifier else Modifier.padding(padding),
        ) {
            composable(Routes.SETUP) {
                SetupScreen(
                    vm = vm,
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                    onCrawlStarted = { navigateTab(Routes.DASHBOARD) },
                )
            }
            composable(Routes.LOGIN) {
                LoginScreen(vm = vm, onDone = { navController.popBackStack() })
            }
            composable(Routes.DASHBOARD) {
                DashboardScreen(vm = vm, onOpenExport = { navigateTab(Routes.EXPORT) })
            }
            composable(Routes.EXPORT) {
                ExportScreen(vm = vm)
            }
        }
    }
}
