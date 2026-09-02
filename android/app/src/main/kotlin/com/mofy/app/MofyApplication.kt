package com.mofy.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mofy.app.data.catalog.CatalogDatabase
import com.mofy.app.data.catalog.CatalogPosterCache
import com.mofy.app.data.catalog.CatalogRepository
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.models.ModelDownloadStatus
import com.mofy.app.data.sites.SiteRepository
import com.mofy.app.data.tmdb.GenreRepository
import com.mofy.app.data.tmdb.TmdbClient
import com.mofy.app.data.tmdb.TmdbSettings
import com.mofy.app.watchtogether.signaling.SignalingSettings
import com.mofy.app.watchtogether.webrtc.PeerConnectionFactoryHolder
import com.mofy.app.workers.CatalogSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.TimeUnit

class MofyApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val KEY_SEARCH_INDEX_BACKFILLED = "search_index_backfilled_v1"
        private const val BACKFILL_BATCH_SIZE = 25
        private const val JITTER_MAX_MILLIS = 6L * 60 * 60 * 1000 // 6 hours

        private fun jitterMillis(): Long = (0 until JITTER_MAX_MILLIS).random()
    }

    override fun onCreate() {
        super.onCreate()
        SignalingSettings.applyBuildConfig(BuildConfig.WT_SIGNALING_URL)
        TmdbSettings.init(this)
        PeerConnectionFactoryHolder.init(this)
        val database = AppDatabase.get(this)
        val genreRepository = GenreRepository(dao = database.genreDao())
        val siteRepository = SiteRepository(dao = database.siteDao())
        applicationScope.launch { genreRepository.ensureSynced() }
        applicationScope.launch { siteRepository.ensureSeeded() }
        applicationScope.launch { backfillCatalogPosters(database) }

        // ADR 0010 task 6: a row left DOWNLOADING means its
        // ModelDownloadService died with the process (killed, crashed) -
        // nothing is actually running to finish it. Mark it FAILED so
        // Settings (task 7) can offer retry instead of showing a stuck
        // "downloading" that will never progress.
        applicationScope.launch {
            val dao = database.modelDownloadDao()
            dao.findByStatus(ModelDownloadStatus.DOWNLOADING.name).forEach { stuck ->
                dao.upsert(
                    stuck.copy(
                        status = ModelDownloadStatus.FAILED.name,
                        lastErrorMessage = "Interrupted (app was killed mid-download)",
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }

        // Periodic TMDB new-releases sync (ADR 0009) - movies (UPCOMING)
        // every ~14 days, network-constrained, KEEP (first enqueue wins;
        // later launches don't reset the schedule). Distinct from the
        // applicationScope.launch startup syncs above - WorkManager owns
        // scheduling/deferral, not a raw coroutine. A manual "Refresh now"
        // one-off is in Settings.
        //
        // Random initial-delay jitter (0-6h) on both requests below: two
        // installs (e.g. two people's phones) enqueuing at literally the
        // same moment would otherwise hit TMDB at the same instant every
        // cycle - jitter only needs to happen once, since KEEP means this
        // device's phase offset is set on first-ever enqueue and periodic
        // work then repeats from actual completion time, not a fixed clock.
        val workManager = WorkManager.getInstance(this)
        val movieSyncRequest = PeriodicWorkRequestBuilder<CatalogSyncWorker>(14, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(jitterMillis(), TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf(com.mofy.app.workers.KEY_SYNC_KINDS to arrayOf("UPCOMING")))
            .build()
        workManager.enqueueUniquePeriodicWork("catalog_sync", ExistingPeriodicWorkPolicy.KEEP, movieSyncRequest)

        // TV (AIRING_TODAY) syncs separately, every 2 days - TV listings
        // churn daily, unlike movie release windows (see
        // SyncedCatalogRepository.ALL_KINDS' doc comment).
        val tvSyncRequest = PeriodicWorkRequestBuilder<CatalogSyncWorker>(2, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(jitterMillis(), TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf(com.mofy.app.workers.KEY_SYNC_KINDS to arrayOf("AIRING_TODAY")))
            .build()
        workManager.enqueueUniquePeriodicWork("catalog_sync_tv", ExistingPeriodicWorkPolicy.KEEP, tvSyncRequest)

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
