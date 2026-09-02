package com.mofy.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mofy.app.data.catalog.SyncedCatalogRepository
import com.mofy.app.data.catalog.SyncedCatalogVecDao
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.tmdb.GenreRepository
import com.mofy.app.search.OnDeviceEmbedder
import com.mofy.app.search.TextEmbedder
import java.util.Locale

/**
 * Periodic job (ADR 0009 task 7) that syncs TMDB's new/upcoming feed kinds
 * into the Room-managed synced catalog tables. Enqueued from
 * MofyApplication.onCreate() as unique periodic work (~14 days,
 * network-constrained); also reused by Settings' "Refresh new releases now"
 * as a one-off work request.
 *
 * buildRepository() is a protected seam so tests can substitute a fake
 * repository without touching the Worker's (Context, WorkerParameters)
 * constructor contract.
 */
open class CatalogSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val embedder = OnDeviceEmbedder(applicationContext)
        // OnDeviceEmbedder downloads its model on first init - best-effort
        // here: embed() returns null if the model isn't ready, and the repo
        // skips vec insert for those rows (row + FTS still sync).
        runCatching { embedder.init() }
        val repository = buildRepository(applicationContext, embedder)
        val region = Locale.getDefault().country.ifEmpty { "US" }
        return try {
            repository.sync(region)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    protected open fun buildRepository(context: Context, embedder: TextEmbedder): SyncedCatalogRepository {
        val database = AppDatabase.get(context)
        return SyncedCatalogRepository(
            dao = database.syncedCatalogDao(),
            searchDao = database.syncedCatalogSearchDao(),
            vecDao = SyncedCatalogVecDao(database),
            embedder = embedder,
            resolveGenreNames = { ids ->
                GenreRepository(dao = database.genreDao()).resolveNames(ids)
            },
        )
    }
}