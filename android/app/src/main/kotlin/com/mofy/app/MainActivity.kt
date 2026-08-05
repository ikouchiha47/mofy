package com.mofy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.ui.browse.BrowseScreen
import com.mofy.app.ui.browse.BrowseSessionViewModel
import com.mofy.app.ui.home.HomeScreen
import com.mofy.app.ui.library.LibraryScreen
import com.mofy.app.ui.nav.PlaceholderScreen
import com.mofy.app.ui.nav.PushedRoute
import com.mofy.app.ui.nav.TopLevelDestination
import com.mofy.app.ui.settings.SettingsScreen
import com.mofy.app.ui.theme.MofyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App is forced dark regardless of system theme (see ADR 0003), so
        // status/nav bar icons must always be light, not auto-detected.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MofyTheme {
                MofyApp()
            }
        }
    }
}

private const val ROUTE_WEBVIEW_PLACEHOLDER = "webview_placeholder/{siteName}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MofyApp() {
    val navController = rememberNavController()
    val browseSessionViewModel = BrowseSessionViewModel()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

    // A single topBar slot for the whole app, keyed off the current route.
    // Screens never render their own TopAppBar - doing that inside a screen
    // AND relying on Scaffold's contentPadding double-counts the status bar
    // inset (that was the padding bug). One slot, one inset calculation.
    Scaffold(
        topBar = {
            when (currentRoute) {
                TopLevelDestination.HOME.route -> TopAppBar(
                    title = { Text("Mofy") },
                    actions = {
                        IconButton(onClick = { /* Watch Together join sheet - Phase 13 */ }) {
                            Icon(Icons.Filled.Groups, contentDescription = "Join a Watch Together session")
                        }
                        IconButton(onClick = { /* Search screen - Phase 09 */ }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    },
                )
                TopLevelDestination.BROWSE.route -> TopAppBar(title = { Text("Browse") })
                ROUTE_WEBVIEW_PLACEHOLDER -> TopAppBar(
                    title = { Text(backStackEntry?.arguments?.getString("siteName") ?: "") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.EDIT_SITE -> TopAppBar(
                    title = { Text("Edit Site") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                PushedRoute.EDIT_SITE_NEW -> TopAppBar(
                    title = { Text("Add Site") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                TopLevelDestination.LIBRARY.route -> TopAppBar(title = { Text("Library") })
                TopLevelDestination.SETTINGS.route -> TopAppBar(title = { Text("Settings") })
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(contentPadding = contentPadding)
            }
            composable(TopLevelDestination.BROWSE.route) {
                BrowseScreen(
                    contentPadding = contentPadding,
                    sessionViewModel = browseSessionViewModel,
                    onSitePicked = { site: TorrentSite ->
                        navController.navigate("webview_placeholder/${site.name}")
                    },
                    onEditSite = { site: TorrentSite? ->
                        val route = site?.let { PushedRoute.editSite(it.name) } ?: PushedRoute.EDIT_SITE_NEW
                        navController.navigate(route)
                    },
                )
            }
            composable(TopLevelDestination.LIBRARY.route) {
                LibraryScreen(contentPadding = contentPadding)
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(contentPadding = contentPadding)
            }
            composable(ROUTE_WEBVIEW_PLACEHOLDER) {
                PlaceholderScreen(contentPadding = contentPadding, note = "WebView browsing lands with Phase 02")
            }
            composable(PushedRoute.EDIT_SITE) { backStack ->
                val siteName = backStack.arguments?.getString("siteName") ?: ""
                PlaceholderScreen(contentPadding = contentPadding, note = "Editing \"$siteName\" - Edit Site screen lands next")
            }
            composable(PushedRoute.EDIT_SITE_NEW) {
                PlaceholderScreen(contentPadding = contentPadding, note = "Edit Site screen lands next")
            }
        }
    }
}
