package com.mofy.app

import android.app.Application
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.sites.SiteRepository
import com.mofy.app.data.tmdb.GenreRepository
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
        val database = AppDatabase.get(this)
        val genreRepository = GenreRepository(dao = database.genreDao())
        val siteRepository = SiteRepository(dao = database.siteDao())
        applicationScope.launch { genreRepository.ensureSynced() }
        applicationScope.launch { siteRepository.ensureSeeded() }

        // One-time backfill of library_search/search_vocab for items saved
        // before those indexes existed - gated by a persisted flag so this
        // never re-scans the whole library on every launch (it would
        // otherwise redo O(items x words) DB work on every cold start).
        // Chunked with a yield() between batches so a large library doesn't
        // hold this coroutine (and hammer SQLite's single writer connection)
        // in one unbroken burst - other startup work gets to interleave.
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
}
