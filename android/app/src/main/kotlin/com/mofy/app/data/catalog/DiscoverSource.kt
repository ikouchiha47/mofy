package com.mofy.app.data.catalog

/**
 * Distinct-source filter for Discover (ADR 0009 task 10) - deliberately NOT
 * a CatalogSort value: a sort order applies to the bundled catalog, while
 * this switches the entire paging source to the synced TMDB feed tables.
 * Sort values from CatalogSort still apply within the bundled source.
 */
enum class DiscoverSource(val label: String) {
    ALL("All"),
    NEW_AND_UPCOMING("Upcoming"),
}