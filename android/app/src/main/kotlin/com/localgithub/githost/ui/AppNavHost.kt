package com.localgithub.githost.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import com.localgithub.githost.ui.theme.GitHub
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.localgithub.githost.EventBus
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private val bottomNavItems = listOf(
    BottomNavItem("repos",   "Repos",   Icons.Default.FolderOpen),
    BottomNavItem("devices", "Devices", Icons.Default.Devices),
    BottomNavItem("settings","Settings",Icons.Default.Settings),
)

data class BottomNavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    // A githost://pair link opens the pair screen, which consumes the link.
    LaunchedEffect(Unit) {
        EventBus.pendingPairUri.collect { uri ->
            if (uri != null && currentDest?.route != "pair") {
                navController.navigate("pair") { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = GitHub.CanvasSubtle) {
                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentDest?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GitHub.Fg,
                            selectedTextColor = GitHub.Fg,
                            indicatorColor = GitHub.BorderMuted,
                            unselectedIconColor = GitHub.FgMuted,
                            unselectedTextColor = GitHub.FgMuted,
                        ),
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "repos",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("repos")   { ReposScreen() }
            composable("devices") { DevicesScreen(navController) }
            composable("settings"){ SettingsScreen() }
            composable("pair")    { PairScreen(navController) }
        }
    }
}
