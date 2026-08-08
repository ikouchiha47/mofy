package com.mofy.app.data.catalog

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.mofy.app.data.library.matchQueryFromTokens
import com.mofy.app.data.library.tokenize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keyset cursor: (last row's sort-column value, last row's tconst) - stable under a fixed ORDER BY, no OFFSET row-scanning cost as pages get deep. */
data class CatalogCursor(val sortValue: Double, val tconst: String)

/**
 * Cursor/keyset pagination over catalog.db, not OFFSET-based - SQLite has
 * to scan and discard every prior row for a large OFFSET, which gets
 * linearly slower as you page deeper. A keyset ("give me rows after this
 * (sortValue, tconst) pair") stays index-friendly at any depth. Paging 3
 * (not hand-rolled scroll tracking) keeps only a bounded window of pages
 * in memory - see DiscoverScreen.
 */
class CatalogPagingSource(
    private val db: SQLiteDatabase,
    private val query: String?,
    private val titleType: String?,
    private val genre: String?,
    private val sort: CatalogSort,
) : PagingSource<CatalogCursor, CatalogItem>() {

    override fun getRefreshKey(state: PagingState<CatalogCursor, CatalogItem>): CatalogCursor? = null

    override suspend fun load(params: LoadParams<CatalogCursor>): LoadResult<CatalogCursor, CatalogItem> =
        withContext(Dispatchers.IO) {
            try {
                val pageSize = params.loadSize
                val matchQuery = query?.let { matchQueryFromTokens(tokenize(it)) }
                if (query != null && matchQuery == null) {
                    return@withContext LoadResult.Page(emptyList(), null, null)
                }

                val conditions = mutableListOf<String>()
                val args = mutableListOf<String>()
                if (matchQuery != null) {
                    conditions += "catalog_fts MATCH ?"
                    args += matchQuery
                }
                if (titleType != null) {
                    conditions += "ci.titleType = ?"
                    args += titleType
                }
                if (genre != null) {
                    conditions += "ci.genres LIKE ?"
                    args += "%$genre%"
                }
                val cursor = params.key
                if (cursor != null) {
                    conditions += "(ci.${sort.column} < ? OR (ci.${sort.column} = ? AND ci.tconst > ?))"
                    args += cursor.sortValue.toString()
                    args += cursor.sortValue.toString()
                    args += cursor.tconst
                }

                val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
                val fromClause = if (matchQuery != null) {
                    "catalog_items ci JOIN catalog_fts f ON f.tconst = ci.tconst"
                } else {
                    "catalog_items ci"
                }
                args += pageSize.toString()

                val items = db.rawQuery(
                    """SELECT ci.* FROM $fromClause
                       $where
                       ORDER BY ci.${sort.column} DESC, ci.tconst ASC
                       LIMIT ?""",
                    args.toTypedArray(),
                ).use { it.readAll() }

                val nextKey = if (items.size == pageSize) {
                    val last = items.last()
                    CatalogCursor(sortValueOf(last, sort), last.tconst)
                } else {
                    null
                }
                LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }

    private fun sortValueOf(item: CatalogItem, sort: CatalogSort): Double = when (sort) {
        CatalogSort.MOST_VOTED -> item.numVotes?.toDouble() ?: 0.0
        CatalogSort.HIGHEST_RATED -> item.averageRating ?: 0.0
        CatalogSort.NEWEST -> item.startYear?.toDouble() ?: 0.0
    }

    private fun Cursor.readAll(): List<CatalogItem> {
        val items = mutableListOf<CatalogItem>()
        while (moveToNext()) {
            items += CatalogItem(
                tconst = getString(getColumnIndexOrThrow("tconst")),
                title = getString(getColumnIndexOrThrow("title")),
                titleType = getString(getColumnIndexOrThrow("titleType")),
                startYear = getIntOrNull("startYear"),
                genres = getStringOrNull("genres"),
                averageRating = getDoubleOrNull("averageRating"),
                numVotes = getIntOrNull("numVotes"),
                overview = getString(getColumnIndexOrThrow("overview")),
            )
        }
        return items
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.getDoubleOrNull(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }
}
