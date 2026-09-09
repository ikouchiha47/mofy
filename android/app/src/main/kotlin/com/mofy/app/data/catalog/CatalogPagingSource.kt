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
    private val genres: Set<String> = emptySet(),
    private val decades: Set<Int> = emptySet(),
    private val runtimeBucket: RuntimeBucket? = null,
    private val minRating: RatingThreshold? = null,
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
                if (genres.isNotEmpty()) {
                    // OR'd within the group (any selected genre matches), the
                    // group itself AND'd with every other filter category.
                    conditions += "(" + genres.joinToString(" OR ") { "ci.genres LIKE ?" } + ")"
                    args += genres.map { "%$it%" }
                }
                if (decades.isNotEmpty()) {
                    conditions += "(" + decades.joinToString(" OR ") { "ci.startYear BETWEEN ? AND ?" } + ")"
                    decades.forEach { decade ->
                        args += decade.toString()
                        args += (decade + 9).toString()
                    }
                }
                if (runtimeBucket != null) {
                    if (runtimeBucket.minMinutes != null) {
                        conditions += "ci.runtimeMinutes >= ?"
                        args += runtimeBucket.minMinutes.toString()
                    }
                    if (runtimeBucket.maxMinutes != null) {
                        conditions += "ci.runtimeMinutes <= ?"
                        args += runtimeBucket.maxMinutes.toString()
                    }
                }
                if (minRating != null) {
                    conditions += "ci.averageRating >= ?"
                    args += minRating.min.toString()
                }
                val cursor = params.key
                if (cursor != null) {
                    conditions += "(ci.${sort.column} < ? OR (ci.${sort.column} = ? AND ci.tconst > ?))"
                    args += cursor.sortValue.toString()
                    args += cursor.sortValue.toString()
                    args += cursor.tconst
                }

                val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
                // catalog_fts is a CONTENTLESS FTS4 table (content='' - see
                // ml/scripts/05_prepare_android_asset.py) - it can only ever
                // return rowid via SELECT, never tconst. catalog_fts.rowid
                // is built equal to catalog_items.rowid by that script, so
                // the join must go through rowid, not tconst - joining on
                // f.tconst always threw (surfaced here as LoadResult.Error,
                // caught by the outer try/catch below), meaning every
                // Discover keyword search failed until this fix.
                val fromClause = if (matchQuery != null) {
                    "catalog_items ci JOIN catalog_fts f ON f.rowid = ci.rowid"
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
                runtimeMinutes = getIntOrNull("runtimeMinutes"),
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
