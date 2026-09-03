package com.mofy.app.data.catalog

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Offset-based paging over AppDatabase's synced_catalog_items (ADR 0009 task
 * 10) - separate from CatalogPagingSource because that source pages the
 * bundled read-only catalog.db and can't cross databases in one query. Plain
 * LIMIT/OFFSET is acceptable here unlike the bundled catalog: this table is
 * small (dozens to low hundreds of rows, refreshed every ~2 weeks), so the
 * linear OFFSET scan cost that motivated CatalogPagingSource's keyset design
 * is irrelevant.
 */
class SyncedCatalogPagingSource(
    private val dao: SyncedCatalogDao,
    // Null = all kinds (Home's Upcoming "More" links pass one - UPCOMING for
    // movies, AIRING_TODAY for TV - so tapping "More" under Upcoming TV
    // doesn't dump you into a movie-heavy combined list).
    private val kind: String? = null,
) : PagingSource<Int, SyncedCatalogItem>() {

    override fun getRefreshKey(state: PagingState<Int, SyncedCatalogItem>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SyncedCatalogItem> {
        return try {
            val offset = params.key ?: 0
            val items = if (kind != null) dao.pageByKind(kind, params.loadSize, offset) else dao.page(params.loadSize, offset)
            val nextKey = if (items.size == params.loadSize) offset + items.size else null
            LoadResult.Page(
                data = items,
                prevKey = if (offset == 0) null else offset - params.loadSize,
                nextKey = nextKey,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}