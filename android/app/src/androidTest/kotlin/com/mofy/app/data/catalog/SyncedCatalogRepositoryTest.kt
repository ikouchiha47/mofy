package com.mofy.app.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.data.tmdb.TmdbApi
import com.mofy.app.data.tmdb.TmdbExternalIdsDto
import com.mofy.app.data.tmdb.TmdbFindResponse
import com.mofy.app.data.tmdb.TmdbGenreListResponse
import com.mofy.app.data.tmdb.TmdbRepository
import com.mofy.app.data.tmdb.TmdbResultDto
import com.mofy.app.data.tmdb.TmdbSearchResponse
import com.mofy.app.search.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0009 task 6 acceptance: SyncedCatalogRepository pulls page-1 results
 * from the four feed kinds, dedupes against already-synced titles, writes
 * each new title to the item table + FTS index + vec0 embedding. Real DAOs
 * on an in-memory AppDatabase; only the network (FakeTmdbApi) and the model
 * (FakeTextEmbedder) are faked.
 */
@RunWith(AndroidJUnit4::class)
class SyncedCatalogRepositoryTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeTmdbApi(private val movies: List<MediaResult>) : TmdbApi {
        override suspend fun upcomingMovies(region: String, page: Int): TmdbSearchResponse =
            TmdbSearchResponse(page = 1, results = movies.map { it.toDto() }, total_pages = 1, total_results = movies.size)

        override suspend fun nowPlayingMovies(region: String, page: Int): TmdbSearchResponse =
            TmdbSearchResponse(page = 1, results = emptyList(), total_pages = 1, total_results = 0)

        override suspend fun onTheAirTv(page: Int): TmdbSearchResponse =
            TmdbSearchResponse(page = 1, results = emptyList(), total_pages = 1, total_results = 0)

        override suspend fun airingTodayTv(timezone: String, page: Int): TmdbSearchResponse =
            TmdbSearchResponse(page = 1, results = emptyList(), total_pages = 1, total_results = 0)

        override suspend fun configurationTimezones(): List<com.mofy.app.data.tmdb.TmdbTimezoneEntry> = emptyList()

        override suspend fun searchMovie(query: String): TmdbSearchResponse = throw UnsupportedOperationException()
        override suspend fun searchTv(query: String): TmdbSearchResponse = throw UnsupportedOperationException()
        override suspend fun movieDetail(id: Int): TmdbResultDto = throw UnsupportedOperationException()
        override suspend fun tvDetail(id: Int): TmdbResultDto = throw UnsupportedOperationException()
        override suspend fun findByImdbId(externalId: String, externalSource: String): TmdbFindResponse = throw UnsupportedOperationException()
        override suspend fun movieExternalIds(id: Int): TmdbExternalIdsDto = throw UnsupportedOperationException()
        override suspend fun tvExternalIds(id: Int): TmdbExternalIdsDto = throw UnsupportedOperationException()
        override suspend fun genreListMovie(): TmdbGenreListResponse = throw UnsupportedOperationException()
        override suspend fun genreListTv(): TmdbGenreListResponse = throw UnsupportedOperationException()

        private fun MediaResult.toDto(): TmdbResultDto = TmdbResultDto(
            id = id,
            title = title,
            name = title,
            overview = overview,
            poster_path = posterPath,
            release_date = releaseDate,
            first_air_date = releaseDate,
            genre_ids = genreIds,
        )
    }

    private class FakeTextEmbedder : TextEmbedder {
        override suspend fun embed(text: String): FloatArray = FloatArray(768) { index -> (index % 7) / 7f }
    }

    private fun buildDb(): AppDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(
                BundledSQLiteDriver().apply {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    addExtension("$nativeLibDir/libvec0", "sqlite3_vec_init")
                },
            )
            .build()
        // See SyncedCatalogDaoTest's buildDb() for why this is needed - an
        // in-memory database never runs MIGRATION_15_16, so synced_catalog_vec
        // (not a Room entity) must be created explicitly here too.
        runBlocking(Dispatchers.IO) {
            db.useWriterConnection { it.usePrepared(SyncedCatalogVecDao.CREATE_TABLE_SQL) { stmt -> stmt.step() } }
        }
        return db
    }

    private val titles = listOf(
        MediaResult(
            id = 1, title = "Dune Part Three", originalTitle = null, romanizedOriginalTitle = null,
            overview = "Paul Atreides returns to Arrakis.", posterPath = "/dune.jpg", year = "2026",
            releaseDate = "2026-11-20", genreIds = listOf(878, 12), voteAverage = 8.0, mediaType = MediaType.MOVIE,
        ),
        MediaResult(
            id = 2, title = "Severance Season 3", originalTitle = null, romanizedOriginalTitle = null,
            overview = "The severed floor reopens.", posterPath = "/sev.jpg", year = "2026",
            releaseDate = "2026-01-15", genreIds = listOf(18, 9648), voteAverage = 8.5, mediaType = MediaType.TV,
        ),
    )

    @Test
    fun syncWritesRowsFtsAndEmbeddings() = runBlocking {
        val db = buildDb()
        try {
            val repo = SyncedCatalogRepository(
                tmdb = TmdbRepository(FakeTmdbApi(titles)),
                dao = db.syncedCatalogDao(),
                searchDao = db.syncedCatalogSearchDao(),
                vecDao = SyncedCatalogVecDao(db),
                embedder = FakeTextEmbedder(),
            )

            repo.sync("US", timezone = "America/New_York")

            val dao = db.syncedCatalogDao()
            val all = dao.page(100, 0)
            assertEquals("both titles should be synced", 2, all.size)

            // FTS-searchable (search across both title + overview).
            val hits = db.syncedCatalogSearchDao().searchItemIds("Dune OR Severance")
            assertEquals(2, hits.size)

            // KNN-queryable: both rows have embeddings inserted (self-match
            // against each row's own stored vector).
            val vecDao = SyncedCatalogVecDao(db)
            val queryVec = FloatArray(768) { index -> (index % 7) / 7f }
            val knnHits = vecDao.knn(queryVec, k = 10)
            assertTrue("expected both synced ids in KNN, got $knnHits", all.map { it.id }.all { it in knnHits })
        } finally {
            db.close()
        }
    }

    @Test
    fun syncTwiceDoesNotDuplicateRows() = runBlocking {
        val db = buildDb()
        try {
            val repo = SyncedCatalogRepository(
                tmdb = TmdbRepository(FakeTmdbApi(titles)),
                dao = db.syncedCatalogDao(),
                searchDao = db.syncedCatalogSearchDao(),
                vecDao = SyncedCatalogVecDao(db),
                embedder = FakeTextEmbedder(),
            )

            repo.sync("US", timezone = "America/New_York")
            repo.sync("US", timezone = "America/New_York")

            val count = db.syncedCatalogDao().page(100, 0)
            assertEquals("dedupe must prevent duplicate rows", 2, count.size)
        } finally {
            db.close()
        }
    }
}