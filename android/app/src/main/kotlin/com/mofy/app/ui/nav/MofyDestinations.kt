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
    const val LINK = "link/{itemId}"
    const val IMPORT_LINK = "import_link"
    const val ADD_MANUALLY = "add_manually"
    const val MANUAL_ENTRY_FORM = "manual_entry_form"
    const val SEARCH = "search"
    // source/sort/type are required path segments with sentinel defaults
    // ("ALL"/"MOST_VOTED"/"ANY"), same convention as RESOLVE_MATCH's
    // existingItemId "none" - lets Home's "More" links open Discover
    // pre-filtered without relying on Compose Navigation's separate
    // optional-query-arg machinery.
    const val DISCOVER = "discover/{source}/{sort}/{type}"
    // Shared by Discover's "+" (no existing item, existingItemId "none") and
    // Detail's "Sync info" when a direct tmdbId fetch isn't possible/fails
    // (existingItemId set - the confirmed match updates that item in place
    // rather than creating a new one).
    const val RESOLVE_MATCH = "resolve_match/{title}/{mediaType}/{existingItemId}"

    // Watch Together (Phase 13) - see docs/tasks/13-watch-together.md C1.
    // Join is a modal bottom sheet from Home, not its own route.
    const val WT_CREATE = "watch_together/create/{libraryItemId}"
    const val WT_SESSION = "watch_together/session"
    const val WT_SCAN = "watch_together/scan"

    fun discover(source: String = "ALL", sort: String = "MOST_VOTED", type: String = "ANY") =
        "discover/$source/$sort/$type"
    fun editSite(siteName: String) = "edit_site/$siteName"
    fun link(itemId: String) = "link/$itemId"
    fun resolveMatch(title: String, mediaType: String, existingItemId: String = "none") =
        "resolve_match/${java.net.URLEncoder.encode(title, "UTF-8")}/$mediaType/$existingItemId"
    fun watchTogetherCreate(libraryItemId: String) = "watch_together/create/$libraryItemId"
}
