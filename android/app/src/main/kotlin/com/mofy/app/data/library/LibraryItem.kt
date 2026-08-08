package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.TMDB_IMAGE_BASE_URL
import java.util.UUID

enum class LibrarySource { SAVED, DOWNLOADED, IMPORTED, MANUAL, DISCOVERED }
enum class PosterSource { TMDB, UPLOADED, NONE }

/**
 * Plain Int, not an enum - a future recommendation engine needs to sum/
 * average/sort by this, and a nullable-String-enum column would mean
 * mapping back to a number on every calculation. No TypeConverter needed
 * either, since Room maps Int straight to SQLite's INTEGER type.
 */
object Feedback {
    const val NOT_INTERESTED = -1
    const val LIKE = 1
    const val SUPER_LIKE = 2
}

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
    // Like / Super Like / Not Interested from Detail's feedback row - see
    // Feedback object; null = no signal yet. Feeds the recommendation
    // engine later; purely stored for now, no scoring logic reads it yet.
    val feedback: Int?,
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
    feedback = null,
)
