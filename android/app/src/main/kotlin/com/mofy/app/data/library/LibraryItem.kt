package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.TMDB_IMAGE_BASE_URL
import java.util.UUID

enum class LibrarySource { SAVED, DOWNLOADED, IMPORTED, MANUAL }
enum class PosterSource { TMDB, UPLOADED, NONE }

/**
 * A saved library entry. `id` is a synthetic identity, deliberately not
 * derived from tmdbId/mediaType - both are nullable (manual, no-TMDB-match
 * entries have neither at first) and mediaType is user-correctable after
 * creation via Detail's Movie/TV chips, so neither can be part of a stable
 * key. See ADR 0005. `(tmdbId, mediaType)` is a plain unique index instead -
 * SQLite treats NULLs as distinct, so manual entries never collide.
 */
@Entity(
    tableName = "library_items",
    indices = [Index(value = ["tmdbId", "mediaType"], unique = true)],
)
data class LibraryItem(
    @PrimaryKey val id: String,
    val tmdbId: Int?,
    val mediaType: String?,
    val title: String,
    // Native-script title and its on-device romanization (see Romanizer.kt)
    // - carried through from MediaResult so Detail's "Search" can offer
    // both as candidate search terms, not just `title`. Neither is
    // guaranteed to match what a release group's filename used, so the
    // user picks - see ConfirmMatchScreen/BrowseScreen search-term chips.
    val originalTitle: String?,
    val romanizedOriginalTitle: String?,
    val overview: String,
    val posterPath: String?,
    val localPosterUri: String?,
    val posterSource: String,
    val year: String?,
    val genreIds: String,
    val genresManual: String?,
    val voteAverage: Double,
    val runtime: Int?,
    val tagline: String?,
    val source: String,
    val addedAtEpochMillis: Long,
    val detailSyncedAtEpochMillis: Long?,
) {
    val posterUrl: String?
        get() = when (posterSource) {
            PosterSource.UPLOADED.name -> localPosterUri
            PosterSource.TMDB.name -> posterPath?.let { "$TMDB_IMAGE_BASE_URL$it" }
            else -> null
        }

    val resolvedGenreNames: List<String>
        get() = genresManual?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val resolvedGenreIds: List<Int>
        get() = genreIds.split(",").mapNotNull { it.toIntOrNull() }
}

fun MediaResult.toLibraryItem(source: LibrarySource): LibraryItem = LibraryItem(
    id = UUID.randomUUID().toString(),
    tmdbId = id,
    mediaType = mediaType.name,
    title = title,
    originalTitle = originalTitle,
    romanizedOriginalTitle = romanizedOriginalTitle,
    overview = overview,
    posterPath = posterPath,
    localPosterUri = null,
    posterSource = PosterSource.TMDB.name,
    year = year,
    genreIds = genreIds.joinToString(","),
    genresManual = null,
    voteAverage = voteAverage,
    runtime = null,
    tagline = null,
    source = source.name,
    addedAtEpochMillis = System.currentTimeMillis(),
    detailSyncedAtEpochMillis = null,
)
