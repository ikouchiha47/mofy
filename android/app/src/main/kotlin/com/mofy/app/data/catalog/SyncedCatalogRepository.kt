package com.mofy.app.data.catalog

import android.util.Log
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.TmdbRepository
import com.mofy.app.data.tmdb.TmdbResult
import com.mofy.app.search.TextEmbedder

private const val TAG = "SyncedCatalogRepository"

/**
 * Fetches TMDB's four new/upcoming feed kinds (ADR 0009), dedupes against
 * already-synced titles, and upserts into the Room-managed synced tables
 * (synced_catalog_items + its FTS index + its vec0 embeddings). Mirrors
 * GenreRepository's constructor-injected-DAO-plus-default-API style.
 *
 * Page 1 only per feed per sync - TMDB's now_playing window is
 * theatrical-release-based and can span 200+ pages; page 1 is the
 * highest-signal slice (see ADR task 6 notes).
 */
class SyncedCatalogRepository(
    private val tmdb: TmdbRepository = TmdbRepository(),
    private val dao: SyncedCatalogDao,
    private val searchDao: SyncedCatalogSearchDao,
    private val vecDao: SyncedCatalogVecDao,
    private val embedder: TextEmbedder,
    private val resolveGenreNames: suspend (List<Int>) -> List<String> = { emptyList() },
) {

    suspend fun sync(region: String) {
        val feeds = listOf(
            "UPCOMING" to tmdb.upcomingMovies(region),
            "NOW_PLAYING" to tmdb.nowPlayingMovies(region),
            "ON_AIR" to tmdb.onTheAirTv(),
            "AIRING_TODAY" to tmdb.airingTodayTv(),
        )
        for ((kind, result) in feeds) {
            if (result is TmdbResult.Failure) {
                // Failure already logged by safeCallWithRetry's caller; skip
                // this kind, don't fail the whole sync.
                Log.w(TAG, "Feed $kind skipped: ${result.error}")
                continue
            }
            val success = result as TmdbResult.Success
            for (media in success.data) {
                val existingId = dao.findId(media.id, media.mediaType.name.lowercase())
                if (existingId != null) continue // dedupe: never re-embed/re-fetch already-synced titles
                val item = media.toSyncedCatalogItem(kind, resolveGenreNames(media.genreIds))
                val ids = dao.upsertAll(listOf(item))
                val newId = ids.first()
                searchDao.reindex(newId, item.title, item.overview)
                val embedding = embedder.embed(item.overview)
                if (embedding != null) {
                    vecDao.insert(newId, embedding)
                } else {
                    Log.w(TAG, "No embedding for ${item.title} - row/FTS synced, vec skipped")
                }
            }
        }
    }

    private suspend fun MediaResult.toSyncedCatalogItem(
        kind: String,
        genreNames: List<String>,
    ): SyncedCatalogItem = SyncedCatalogItem(
        tmdbId = id,
        mediaType = mediaType.name.lowercase(),
        title = title,
        overview = overview,
        posterUrl = posterUrl,
        releaseDate = releaseDate,
        genres = genreNames.takeIf { it.isNotEmpty() }?.joinToString(","),
        kind = kind,
        firstSeenEpochMillis = System.currentTimeMillis(),
    )
}