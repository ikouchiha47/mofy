package com.mofy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.library.LibrarySource
import com.mofy.app.data.library.toLibraryItem
import com.mofy.app.data.sites.SiteCatalog
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.ui.browse.BrowseScreen
import com.mofy.app.ui.browse.BrowseSessionViewModel
import com.mofy.app.ui.browse.TorrentWebViewScreen
import com.mofy.app.ui.confirm.ConfirmMatchScreen
import com.mofy.app.ui.home.HomeScreen
import kotlinx.coroutines.launch
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

private const val ROUTE_WEBVIEW = "webview/{siteName}"
private const val ROUTE_CONFIRM_MATCH = "confirm_match"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MofyApp() {
    val navController = rememberNavController()
    // remember is required here - MofyApp recomposes on every navigation
    // (currentRoute changes), and without it this would silently reset the
    // selected category/extracted title on every screen transition.
    val browseSessionViewModel = remember { BrowseSessionViewModel() }

    val context = LocalContext.current
    val database = remember { AppDatabase.get(context) }
    val coroutineScope = rememberCoroutineScope()

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
                ROUTE_WEBVIEW -> TopAppBar(
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
                ROUTE_CONFIRM_MATCH -> TopAppBar(
                    title = {
                        Column {
                            Text("Confirm Match")
                            Text(
                                "Magnet link captured",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
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
                HomeScreen(contentPadding = contentPadding, libraryDao = database.libraryDao())
            }
            composable(TopLevelDestination.BROWSE.route) {
                BrowseScreen(
                    contentPadding = contentPadding,
                    sessionViewModel = browseSessionViewModel,
                    onSitePicked = { site: TorrentSite ->
                        navController.navigate("webview/${site.name}")
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
            composable(ROUTE_WEBVIEW) { backStack ->
                val siteName = backStack.arguments?.getString("siteName") ?: ""
                val site = SiteCatalog.byName(siteName)
                if (site != null) {
                    TorrentWebViewScreen(
                        contentPadding = contentPadding,
                        site = site,
                        sessionViewModel = browseSessionViewModel,
                        onMagnetCaptured = { navController.navigate(ROUTE_CONFIRM_MATCH) },
                    )
                } else {
                    PlaceholderScreen(contentPadding = contentPadding, note = "Unknown site \"$siteName\"")
                }
            }
            composable(ROUTE_CONFIRM_MATCH) {
                val extractedTitle by browseSessionViewModel.extractedTitle.collectAsState()
                val category by browseSessionViewModel.selectedCategory.collectAsState()
                // Title is guaranteed by construction by the time a magnet tap
                // navigates here: BrowseSessionViewModel.onMagnetTapped sets it
                // from the magnet URI's dn= param synchronously, falling back
                // to whatever the CSS-selector page extraction already found.
                // This check is a type-safety formality, not a real fallback path.
                if (extractedTitle != null && category != null) {
                    ConfirmMatchScreen(
                        contentPadding = contentPadding,
                        extractedTitle = extractedTitle!!,
                        mediaType = category!!,
                        onConfirm = {
                            // No torrent engine yet (Phase 03) - just close
                            // the loop back to Browse for now.
                            browseSessionViewModel.clearAfterConfirm()
                            navController.popBackStack(TopLevelDestination.BROWSE.route, inclusive = false)
                        },
                        onSaveToLibrary = { results ->
                            coroutineScope.launch {
                                results.forEach { database.libraryDao().upsert(it.toLibraryItem(LibrarySource.SAVED)) }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (results.size == 1) "Saved to library" else "Saved ${results.size} to library",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            // Same as the back button: single pop back into the
                            // WebView, which restores the exact page you were on.
                            navController.popBackStack()
                        },
                    )
                } else {
                    PlaceholderScreen(contentPadding = contentPadding, note = "No magnet link captured yet")
                }
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
