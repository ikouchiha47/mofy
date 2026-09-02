package com.mofy.app.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mofy.app.data.library.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0009 task 3 acceptance: SyncedCatalogDao/SearchDao/VecDao against a
 * real in-memory AppDatabase (vec0 extension loaded, same as the app). Covers
 * upsert+recentByKind, the manual FTS reindex → MATCH round-trip, and vec0
 * KNN self-match.
 */
@RunWith(AndroidJUnit4::class)
class SyncedCatalogDaoTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun buildDb(): AppDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(
                BundledSQLiteDriver().apply {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    addExtension("$nativeLibDir/libvec0", "sqlite3_vec_init")
                },
            )
            .build()
        // An in-memory database is created directly at the latest version
        // via Room's entity schema, never via MIGRATION_15_16 (nothing to
        // migrate from) - synced_catalog_vec isn't a Room entity, so it
        // must be created explicitly here too, or every vec0 call below
        // fails with "no such table: synced_catalog_vec" (confirmed by an
        // actual failing run before this fix, not assumed).
        runBlocking(Dispatchers.IO) {
            db.useWriterConnection { it.usePrepared(SyncedCatalogVecDao.CREATE_TABLE_SQL) { stmt -> stmt.step() } }
        }
        return db
    }

    private val item = SyncedCatalogItem(
        tmdbId = 101,
        mediaType = "movie",
        title = "Dune Part Three",
        overview = "Paul Atreides returns to Arrakis one last time.",
        posterUrl = "https://image.tmdb.org/t/p/w342/x.jpg",
        releaseDate = "2026-11-20",
        genres = "Sci-Fi,Adventure",
        kind = "UPCOMING",
        firstSeenEpochMillis = 1_000L,
    )

    @Test
    fun upsertThenRecentByKindReturnsItem() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.syncedCatalogDao()
            val ids = dao.upsertAll(listOf(item))
            assertEquals(1, ids.size)

            val recent = dao.recentByKind("UPCOMING", 10)
            assertEquals(1, recent.size)
            assertEquals("Dune Part Three", recent[0].title)

            val foundId = dao.findId(101, "movie")
            assertEquals(ids[0], foundId)
        } finally {
            db.close()
        }
    }

    @Test
    fun reindexProducesExactlyOneSearchableFtsRow() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.syncedCatalogDao()
            val ids = dao.upsertAll(listOf(item))
            val id = ids[0]

            db.syncedCatalogSearchDao().reindex(id, item.title, item.overview)

            val hits = db.syncedCatalogSearchDao().searchItemIds("Dune")
            assertEquals("expected exactly one FTS row for 'Dune'", listOf(id), hits)
        } finally {
            db.close()
        }
    }

    @Test
    fun vecInsertAndKnnSelfMatch() = runBlocking {
        val db = buildDb()
        try {
            val dao = db.syncedCatalogDao()
            val ids = dao.upsertAll(listOf(item))
            val id = ids[0]

            val vecDao = SyncedCatalogVecDao(db)
            val embedding = FloatArray(768) { index -> (index % 10) / 10f }
            vecDao.insert(id, embedding)

            val matches = vecDao.knn(embedding, k = 5)
            assertTrue("expected self-match in KNN results, got $matches", id in matches)
        } finally {
            db.close()
        }
    }
}