package com.mofy.app.ui.nav

import com.mofy.app.R

/**
 * The 4 bottom-nav tabs - see docs/adrs/0003-app-navigation-and-screen-flow.md.
 * iconRes (not ImageVector) - this enum's constants are evaluated eagerly at
 * class init, not inside a @Composable, so ImageVector.vectorResource() (the
 * local-drawable replacement for material-icons-extended's Icons.Filled.*,
 * see res/drawable/ic_*.xml) can't be called here; it's resolved at the
 * single render call site instead (MainActivity's NavigationBarItem icon).
 */
enum class TopLevelDestination(val route: String, val label: String, val iconRes: Int) {
    HOME("home", "Home", R.drawable.ic_home),
    BROWSE("browse", "Browse", R.drawable.ic_explore),
    LIBRARY("library", "Library", R.drawable.ic_video_library),
    SETTINGS("settings", "Settings", R.drawable.ic_settings),
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

    // Watch Together (Phase 13) - see docs/tasks/13-watch-together.md C1
    // and docs/tasks/watch-together-redesign.md. Join is a modal bottom
    // sheet reached from WT_LISTING, not its own route.
    const val WT_LISTING = "watch_together/listing"
    const val WT_CREATE = "watch_together/create/{libraryItemId}"
    // Resumes an *existing* host session's waiting room from the Listing -
    // WT_CREATE always creates a brand new session, which would double up
    // signaling if reused here.
    const val WT_ROOM = "watch_together/room/{roomKey}"
    // roomKey (not "the" session) - a device can be in multiple concurrent
    // Watch Together sessions (SessionLimits.MAX_CONCURRENT_SESSIONS).
    const val WT_SESSION = "watch_together/session/{roomKey}"
    const val WT_SCAN = "watch_together/scan"

    // In-app VLC playback for a single local/content file, no Watch
    // Together session - see SoloPlayerScreen's doc comment. subtitleUri is
    // "none" (sentinel, same convention as RESOLVE_MATCH's existingItemId)
    // when the link has no subtitle. libraryItemId lets the player save
    // watch progress back onto the right LibraryItem row.
    const val SOLO_PLAY = "solo_play/{libraryItemId}/{uri}/{subtitleUri}/{subtitle2Uri}"

    fun discover(source: String = "ALL", sort: String = "MOST_VOTED", type: String = "ANY") =
        "discover/$source/$sort/$type"
    fun editSite(siteName: String) = "edit_site/$siteName"
    fun link(itemId: String) = "link/$itemId"
    fun resolveMatch(title: String, mediaType: String, existingItemId: String = "none") =
        "resolve_match/${java.net.URLEncoder.encode(title, "UTF-8")}/$mediaType/$existingItemId"
    fun watchTogetherCreate(libraryItemId: String) = "watch_together/create/$libraryItemId"
    fun wtSession(roomKey: String) = "watch_together/session/$roomKey"
    fun wtRoom(roomKey: String) = "watch_together/room/$roomKey"
    fun soloPlay(libraryItemId: String, uri: String, subtitleUri: String? = null, subtitle2Uri: String? = null): String {
        fun seg(s: String?) = s?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: "none"
        return "solo_play/${seg(libraryItemId)}/${seg(uri)}/${seg(subtitleUri)}/${seg(subtitle2Uri)}"
    }
}
