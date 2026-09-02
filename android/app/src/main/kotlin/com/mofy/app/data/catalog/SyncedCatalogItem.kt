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
    val firstSeenEpochMillis: Long,
)