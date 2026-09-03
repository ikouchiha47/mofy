package com.mofy.app.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncedCatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SyncedCatalogItem>): List<Long> // returns rowids, needed by SyncedCatalogVecDao

    @Query("SELECT * FROM synced_catalog_items WHERE kind = :kind ORDER BY firstSeenEpochMillis DESC LIMIT :limit")
    suspend fun recentByKind(kind: String, limit: Int): List<SyncedCatalogItem>

    @Query("SELECT * FROM synced_catalog_items ORDER BY firstSeenEpochMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<SyncedCatalogItem>

    @Query("SELECT * FROM synced_catalog_items WHERE kind = :kind ORDER BY firstSeenEpochMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun pageByKind(kind: String, limit: Int, offset: Int): List<SyncedCatalogItem>

    @Query("SELECT id FROM synced_catalog_items WHERE tmdbId = :tmdbId AND mediaType = :mediaType")
    suspend fun findId(tmdbId: Int, mediaType: String): Long?

    /**
     * Combined "Upcoming" feed across all kinds — needed by task 9.
     * Returns most recent items regardless of kind.
     */
    @Query("SELECT * FROM synced_catalog_items ORDER BY firstSeenEpochMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SyncedCatalogItem>
}