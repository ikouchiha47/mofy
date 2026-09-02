package com.mofy.app.data.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "synced_catalog_items",
    indices = [Index(value = ["tmdbId", "mediaType"], unique = true)],
)
data class SyncedCatalogItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tmdbId: Int,
    val mediaType: String,           // "movie" | "tv"
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val releaseDate: String?,        // ISO 8601 date, nullable (TBA titles)
    val genres: String?,             // comma-separated, mirrors CatalogItem
    val kind: String,                // UPCOMING | NOW_PLAYING | ON_AIR | AIRING_TODAY
    // Name is stale - column is actually "syncedAt": overwritten to now() on
    // EVERY sync hit (new row or a title we already had), not just on first
    // insert. Kept as one field, not split into created/updated - nothing
    // in the product needs "when did we first see this" separately from
    // "is this still confirmed relevant." Rename to syncedAtEpochMillis in
    // a later pass (needs a schema migration); the field's behavior below
    // in SyncedCatalogRepository.sync() is what actually matters now.
    val firstSeenEpochMillis: Long,
)