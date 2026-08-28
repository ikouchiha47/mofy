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
        try {
            val catalogDb = CatalogDatabase.get(this)
            val repo = CatalogRepository(catalogDb)
            val tconsts = (repo.popularItems(6) + repo.newReleases(6))
                .map { it.tconst }.distinct()
            val cacheDao = database.catalogPosterCacheDao()
            val cached = cacheDao.getCachedTconsts(tconsts).toSet()
            val missing = tconsts.filter { it !in cached }
            if (missing.isEmpty()) return
            val fetched = missing.mapNotNull { tconst ->
                runCatching {
                    val result = TmdbClient.api.findByImdbId(tconst)
                    CatalogPosterCache(tconst = tconst, posterPath = result.posterPath)
                }.getOrNull().also { yield() }
            }
            if (fetched.isNotEmpty()) cacheDao.upsertAll(fetched)
        } catch (_: Exception) {}
    }
}
