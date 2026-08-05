package com.mofy.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/** The 4 bottom-nav tabs - see docs/adrs/0003-app-navigation-and-screen-flow.md. */
enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    BROWSE("browse", "Browse", Icons.Filled.Explore),
    LIBRARY("library", "Library", Icons.Filled.VideoLibrary),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/** Pushed routes reached from within a tab - no bottom nav on these. */
object PushedRoute {
    const val EDIT_SITE = "edit_site/{siteName}"
    const val EDIT_SITE_NEW = "edit_site_new"

    fun editSite(siteName: String) = "edit_site/$siteName"
}
