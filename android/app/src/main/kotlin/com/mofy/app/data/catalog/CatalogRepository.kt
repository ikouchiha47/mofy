package com.mofy.app.data.catalog

import android.database.sqlite.SQLiteDatabase
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

private const val PAGE_SIZE = 40

class CatalogRepository(private val db: SQLiteDatabase) {

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
}
