package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.MediaType

/**
 * A saved library entry. May or may not have a local file yet - "Save to
 * Library" (no download) and a future confirmed download both write here,
 * see docs/phases/06-local-library.md. Source distinguishes how it got in.
 */
@Entity(tableName = "library_items")
data class LibraryItem(
    @PrimaryKey val key: String,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val year: String?,
    val genreIds: String,
    val voteAverage: Double,
    val source: String,
    val addedAtEpochMillis: Long,
)

enum class LibrarySource { SAVED, DOWNLOADED, IMPORTED }

fun MediaResult.toLibraryItem(source: LibrarySource): LibraryItem = LibraryItem(
    key = "${mediaType.name}_$id",
    tmdbId = id,
    mediaType = mediaType.name,
    title = title,
    overview = overview,
    posterPath = posterPath,
    year = year,
    genreIds = genreIds.joinToString(","),
    voteAverage = voteAverage,
    source = source.name,
    addedAtEpochMillis = System.currentTimeMillis(),
)

fun LibraryItem.toMediaResult(): MediaResult = MediaResult(
    id = tmdbId,
    title = title,
    overview = overview,
    posterPath = posterPath,
    year = year,
    genreIds = genreIds.split(",").mapNotNull { it.toIntOrNull() },
    voteAverage = voteAverage,
    mediaType = MediaType.valueOf(mediaType),
)
