package com.mofy.app.data.catalog

import com.mofy.app.data.library.LibraryItem
import com.mofy.app.data.library.LibrarySource
import com.mofy.app.data.library.PosterSource
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.data.tmdb.TMDB_IMAGE_BASE_URL
import java.util.UUID

/**
 * No tmdbId - catalog rows are IMDb-only, not TMDB-matched (see
 * ml/README.md). genresManual (not genreIds) carries the genre names
 * straight from IMDb's own genres column, same field ManualEntryScreen
 * uses for hand-typed genres - there's no TMDB genre-ID mapping to attach.
 * posterUrl (if the catalog poster backfill already resolved one) is
 * carried over as posterPath/posterSource=TMDB rather than dropped - a Home/
 * Discover card that already shows an image shouldn't go blank on Detail
 * just because there's no tmdbId yet to re-fetch it from.
 */
fun CatalogItem.toLibraryItem(): LibraryItem = LibraryItem(
    id = UUID.randomUUID().toString(),
    tmdbId = null,
    mediaType = if (titleType == "tvSeries") MediaType.TV.name else MediaType.MOVIE.name,
    title = title,
    originalTitle = null,
    romanizedOriginalTitle = null,
    overview = overview,
    posterPath = posterUrl?.removePrefix(TMDB_IMAGE_BASE_URL),
    localPosterUri = null,
    posterSource = if (posterUrl != null) PosterSource.TMDB.name else PosterSource.NONE.name,
    year = startYear?.toString(),
    genreIds = "",
    genresManual = genres,
    voteAverage = averageRating ?: 0.0,
    runtime = runtimeMinutes,
    tagline = null,
    source = LibrarySource.DISCOVERED.name,
    addedAtEpochMillis = System.currentTimeMillis(),
    detailSyncedAtEpochMillis = null,
    feedback = null,
    imdbId = tconst,
)
