package com.mofy.app

import android.app.Application
import com.mofy.app.data.catalog.CatalogDatabase
import com.mofy.app.data.catalog.CatalogPosterCache
import com.mofy.app.data.catalog.CatalogRepository
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.sites.SiteRepository
import com.mofy.app.data.tmdb.GenreRepository
import com.mofy.app.data.tmdb.TmdbClient
import com.mofy.app.watchtogether.signaling.SignalingSettings
import com.mofy.app.watchtogether.webrtc.PeerConnectionFactoryHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class MofyApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val KEY_SEARCH_INDEX_BACKFILLED = "search_index_backfilled_v1"
        private const val BACKFILL_BATCH_SIZE = 25
    }

    override fun onCreate() {
        super.onCreate()
        SignalingSettings.applyBuildConfig(BuildConfig.WT_SIGNALING_URL)
        PeerConnectionFactoryHolder.init(this)
        val database = AppDatabase.get(this)
        val genreRepository = GenreRepository(dao = database.genreDao())
        val siteRepository = SiteRepository(dao = database.siteDao())
        applicationScope.launch { genreRepository.ensureSynced() }
        applicationScope.launch { siteRepository.ensureSeeded() }
        applicationScope.launch { backfillCatalogPosters(database) }

        // One-time backfill of library_search/search_vocab for items saved
        // before those indexes existed.
        val prefs = getSharedPreferences("mofy_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SEARCH_INDEX_BACKFILLED, false)) {
            applicationScope.launch {
                database.libraryDao().getAllOnce()
                    .chunked(BACKFILL_BATCH_SIZE)
                    .forEach { batch ->
                        batch.forEach { database.libraryDao().reindexSearch(it) }
                        yield()
                    }
                prefs.edit().putBoolean(KEY_SEARCH_INDEX_BACKFILLED, true).apply()
            }
        }
    }

    private suspend fun backfillCatalogPosters(database: AppDatabase) {
        android.util.Log.d("CatalogPosterBackfill", "entered")
        try {
            android.util.Log.d("CatalogPosterBackfill", "starting")
            val catalogDb = CatalogDatabase.get(this)
            val repo = CatalogRepository(catalogDb)
            val homeGenres = listOf("Action", "Drama", "Comedy", "Thriller", "Sci-Fi", "Horror")
            val tconsts = (
                repo.popularItems(6) +
                repo.newReleases(6) +
                homeGenres.flatMap { repo.byGenre(it, 6) }
            ).map { it.tconst }.distinct()
            android.util.Log.d("CatalogPosterBackfill", "tconsts=$tconsts")
            val cacheDao = database.catalogPosterCacheDao()
            val cached = cacheDao.getCachedTconsts(tconsts).toSet()
            val missing = tconsts.filter { it !in cached }
            android.util.Log.d("CatalogPosterBackfill", "missing=${missing.size}")
            if (missing.isEmpty()) return
            var saved = 0
            missing.forEach { tconst ->
                runCatching {
                    val result = TmdbClient.api.findByImdbId(tconst)
                    android.util.Log.d("CatalogPosterBackfill", "$tconst -> poster=${result.posterPath}")
                    if (result.posterPath != null) {
                        cacheDao.upsert(CatalogPosterCache(tconst = tconst, posterPath = result.posterPath))
                        saved++
                    }
                }.onFailure { android.util.Log.e("CatalogPosterBackfill", "$tconst failed: $it") }
                yield()
            }
            android.util.Log.d("CatalogPosterBackfill", "done, saved $saved/${missing.size} posters")
        } catch (e: Exception) {
            android.util.Log.e("CatalogPosterBackfill", "outer failure: $e")
        }
    }
}
