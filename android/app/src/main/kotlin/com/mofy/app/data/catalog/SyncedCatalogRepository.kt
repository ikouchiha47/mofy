package com.mofy.app.data.catalog

import android.util.Log
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.TmdbRepository
import com.mofy.app.data.tmdb.TmdbResult
import com.mofy.app.search.TextEmbedder

private const val TAG = "SyncedCatalogRepository"

// NOW_PLAYING (movies) and ON_AIR (tv) are deliberately not synced: ON_AIR's
// results are unreliable without passing TMDB's timezone param (which we
// don't - AIRING_TODAY doesn't need it and is sufficient for "new" TV),
// and NOW_PLAYING isn't needed right now alongside UPCOMING. Don't add
// either back without wiring the timezone/region param they actually need.
val ALL_KINDS = setOf("UPCOMING", "AIRING_TODAY")
val TV_KINDS = setOf("AIRING_TODAY")

/**
 * Fetches TMDB's new/upcoming feed kinds (ADR 0009 - currently UPCOMING
 * movies and AIRING_TODAY tv only, see ALL_KINDS), dedupes against
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

    /**
     * @param kinds which feed kinds to sync this call - lets TV
     * (ON_AIR/AIRING_TODAY) run on its own, more frequent schedule than
     * movies (UPCOMING/NOW_PLAYING). TV listings churn daily (a show
     * "airing today" is only accurate for ~24h - TMDB's own docs note
     * air-date/timezone fuzziness, e.g. late-night Japanese broadcasts
     * logged past midnight), while movie release windows move far slower.
     * Defaults to all four kinds for the existing 14-day full sync and the
     * manual "Refresh now" trigger.
     */
    suspend fun sync(region: String, kinds: Set<String> = ALL_KINDS, timezone: String) {
        val feeds = buildList {
            if ("UPCOMING" in kinds) add("UPCOMING" to tmdb.upcomingMovies(region))
            if ("AIRING_TODAY" in kinds) add("AIRING_TODAY" to tmdb.airingTodayTv(timezone))
        }
        for ((kind, result) in feeds) {
            if (result is TmdbResult.Failure) {
                // Failure already logged by safeCallWithRetry's caller; skip
                // this kind, don't fail the whole sync.
                Log.w(TAG, "Feed $kind skipped: ${result.error}")
                continue
            }
            val success = result as TmdbResult.Success
            for (media in success.data) {
                // Always upsert, never permanently skip - a title already
                // in the table gets its metadata + firstSeenEpochMillis
                // (really "last synced at", see SyncedCatalogItem's doc)
                // refreshed on every sync hit, not just its first. Matters
                // for a re-released movie or a still-airing TV show:
                // without this, both would go stale forever after the
                // first sync ever saw them.
                val existingId = dao.findId(media.id, media.mediaType.name.lowercase())
                val item = media.toSyncedCatalogItem(kind, resolveGenreNames(media.genreIds), id = existingId ?: 0)
                // id explicitly carried over on REPLACE - without it, the
                // unique-index conflict resolution deletes the old row and
                // autogenerates a new id, orphaning the FTS row and vec0
                // embedding still pointing at the old one.
                val ids = dao.upsertAll(listOf(item))
                val rowId = ids.first()
                searchDao.reindex(rowId, item.title, item.overview)
                // Only compute a NEW embedding for genuinely new rows - an
                // already-embedded title being re-synced (still airing,
                // still upcoming) doesn't need re-embedding every 2-14 days.
                if (existingId == null) {
                    val embedding = embedder.embed(item.overview)
                    if (embedding != null) {
                        vecDao.insert(rowId, embedding)
                    } else {
                        Log.w(TAG, "No embedding for ${item.title} - row/FTS synced, vec skipped")
                    }
                }
            }
        }
    }

    private suspend fun MediaResult.toSyncedCatalogItem(
        kind: String,
        genreNames: List<String>,
        id: Long,
    ): SyncedCatalogItem = SyncedCatalogItem(
        id = id,
        tmdbId = this.id,
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