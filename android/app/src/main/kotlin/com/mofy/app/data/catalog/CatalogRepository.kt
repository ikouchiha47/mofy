package com.mofy.app.data.catalog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.mofy.app.data.library.LibraryDao
import com.mofy.app.search.DefaultRrfRanker
import com.mofy.app.search.FacetDecoder
import com.mofy.app.search.OnDeviceEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val PAGE_SIZE = 40
private const val SEMANTIC_K = 60

class CatalogRepository(
    private val db: SQLiteDatabase,
    private val libraryDao: LibraryDao? = null,
) {

    /** Cursor-paginated stream over catalog.db - see CatalogPagingSource. Empty `query` means browse (no keyword filter). */
    fun pagedItems(
        query: String? = null,
        titleType: String? = null,
        genre: String? = null,
        sort: CatalogSort = CatalogSort.MOST_VOTED,
    ): Flow<PagingData<CatalogItem>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE),
        pagingSourceFactory = { CatalogPagingSource(db, query?.takeIf { it.isNotBlank() }, titleType, genre, sort) },
    ).flow

    /**
     * Semantic search: embed query → KNN in catalog_vec.db + FTS keyword
     * search + genre boost from facets → RRF fusion. Also cosine-searches
     * library items that have stored embeddings (user-added titles not in
     * catalog.db).
     */
    suspend fun semanticSearch(
        query: String,
        context: Context,
        embedder: OnDeviceEmbedder,
        facetDecoder: FacetDecoder,
    ): List<CatalogItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val facets = facetDecoder.decode(query)
        val queryVec = embedder.embed(query)

        // --- Track B: embedding KNN via catalog_vec.db ---
        val embeddingRanks: Map<String, Int> = if (queryVec != null) {
            VecDatabase.knn(context, queryVec, SEMANTIC_K)
                .mapIndexed { rank, (tconst, _) -> tconst to rank + 1 }
                .toMap()
        } else emptyMap()

        // --- Track A: FTS keyword search ---
        val keywordRanks: Map<String, Int> = ftsSearch(query, SEMANTIC_K)
            .mapIndexed { rank, tconst -> tconst to rank + 1 }
            .toMap()

        // --- Genre boost: catalog items matching detected genres ---
        val genreRanks: Map<String, Int> = if (facets.genres.isNotEmpty()) {
            genreSearch(facets.genres, facets.excludedGenres, SEMANTIC_K)
                .mapIndexed { rank, tconst -> tconst to rank + 1 }
                .toMap()
        } else emptyMap()

        // --- RRF fusion ---
        val ranker = DefaultRrfRanker()
        val fusedIds = ranker.fuse(embeddingRanks, keywordRanks, genreRanks, k = 60)

        // --- Fetch CatalogItem details for top results ---
        val catalogResults = fetchByTconsts(fusedIds.take(50))

        // --- Library items with embeddings (user-added titles) ---
        val libraryResults = libraryItemCatalogItems(queryVec)

        // Merge: catalog first, then library items not already present
        val seen = catalogResults.map { it.tconst }.toHashSet()
        catalogResults + libraryResults.filter { it.tconst !in seen }
    }

    suspend fun popularItems(limit: Int = 20): List<CatalogItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT tconst, title, titleType, startYear, genres, averageRating, numVotes, overview, runtimeMinutes " +
                "FROM catalog_items WHERE averageRating IS NOT NULL AND numVotes >= 50000 " +
                "ORDER BY numVotes DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { it.toCatalogItems() }
    }

    suspend fun newReleases(limit: Int = 20): List<CatalogItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT tconst, title, titleType, startYear, genres, averageRating, numVotes, overview, runtimeMinutes " +
                "FROM catalog_items WHERE startYear >= 2022 AND numVotes >= 5000 " +
                "ORDER BY startYear DESC, numVotes DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { it.toCatalogItems() }
    }

    suspend fun byGenre(genre: String, limit: Int = 20): List<CatalogItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT tconst, title, titleType, startYear, genres, averageRating, numVotes, overview, runtimeMinutes " +
                "FROM catalog_items WHERE genres LIKE ? AND numVotes >= 10000 " +
                "ORDER BY numVotes DESC LIMIT ?",
            arrayOf("%$genre%", limit.toString()),
        ).use { it.toCatalogItems() }
    }

    private fun android.database.Cursor.toCatalogItems(): List<CatalogItem> = buildList {
        while (moveToNext()) {
            add(CatalogItem(
                tconst = getString(0),
                title = getString(1),
                titleType = getString(2),
                startYear = if (isNull(3)) null else getInt(3),
                genres = if (isNull(4)) null else getString(4),
                averageRating = if (isNull(5)) null else getDouble(5),
                numVotes = if (isNull(6)) null else getInt(6),
                overview = getString(7) ?: "",
                runtimeMinutes = if (isNull(8)) null else getInt(8),
            ))
        }
    }

    private fun ftsSearch(query: String, limit: Int): List<String> {
        val ftsQuery = query.trim().split(Regex("\\s+")).joinToString(" OR ") { "\"$it\"" }
        return try {
            db.rawQuery(
                "SELECT ci.tconst FROM catalog_fts f " +
                    "JOIN catalog_items ci ON ci.tconst = f.tconst " +
                    "WHERE catalog_fts MATCH ? LIMIT ?",
                arrayOf(ftsQuery, limit.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun genreSearch(genres: List<String>, excluded: List<String>, limit: Int): List<String> {
        val inclCond = genres.joinToString(" OR ") { "ci.genres LIKE ?" }
        val exclCond = if (excluded.isEmpty()) "" else
            " AND NOT (" + excluded.joinToString(" OR ") { "ci.genres LIKE ?" } + ")"
        val inclArgs = genres.map { "%$it%" }
        val exclArgs = excluded.map { "%$it%" }
        return db.rawQuery(
            "SELECT ci.tconst FROM catalog_items ci WHERE ($inclCond)$exclCond LIMIT ?",
            (inclArgs + exclArgs + listOf(limit.toString())).toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun fetchByTconsts(tconsts: List<String>): List<CatalogItem> {
        if (tconsts.isEmpty()) return emptyList()
        val placeholders = tconsts.joinToString(",") { "?" }
        val rows = db.rawQuery(
            "SELECT tconst, title, titleType, startYear, genres, averageRating, numVotes, overview, runtimeMinutes " +
                "FROM catalog_items WHERE tconst IN ($placeholders)",
            tconsts.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CatalogItem(
                            tconst = cursor.getString(0),
                            title = cursor.getString(1),
                            titleType = cursor.getString(2),
                            startYear = cursor.getInt(3).takeIf { !cursor.isNull(3) },
                            genres = cursor.getString(4),
                            averageRating = cursor.getDouble(5).takeIf { !cursor.isNull(5) },
                            numVotes = cursor.getInt(6).takeIf { !cursor.isNull(6) },
                            overview = cursor.getString(7),
                            runtimeMinutes = cursor.getInt(8).takeIf { !cursor.isNull(8) },
                        ),
                    )
                }
            }
        }
        // Re-order to match RRF rank order
        val byTconst = rows.associateBy { it.tconst }
        return tconsts.mapNotNull { byTconst[it] }
    }

    private suspend fun libraryItemCatalogItems(queryVec: FloatArray?): List<CatalogItem> {
        val dao = libraryDao ?: return emptyList()
        val items = dao.getAllWithEmbedding()
        if (items.isEmpty() || queryVec == null) return emptyList()

        return items
            .mapNotNull { item ->
                val blob = item.embeddingBlob ?: return@mapNotNull null
                val vec = blobToFloats(blob)
                val score = cosine(queryVec, vec)
                if (score < 0.3f) return@mapNotNull null  // discard distant matches
                score to CatalogItem(
                    tconst = "lib:${item.id}",
                    title = item.title,
                    titleType = item.mediaType ?: "movie",
                    startYear = item.year?.toIntOrNull(),
                    genres = item.genresManual ?: "",
                    averageRating = item.voteAverage,
                    numVotes = null,
                    overview = item.overview,
                    runtimeMinutes = item.runtime,
                )
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    private fun blobToFloats(blob: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buf.float }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na.toDouble()) * sqrt(nb.toDouble())
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
