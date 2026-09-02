package com.mofy.app.data.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SyncedCatalogSearchDao {
    @Insert
    suspend fun insertSearchRow(row: SyncedCatalogSearchEntity)

    @Query("DELETE FROM synced_catalog_search WHERE itemId = :itemId")
    suspend fun deleteSearchRow(itemId: Long)

    @Transaction
    suspend fun reindex(itemId: Long, title: String, overview: String) {
        deleteSearchRow(itemId)
        insertSearchRow(SyncedCatalogSearchEntity(itemId = itemId, title = title, overview = overview))
    }

    @Query("SELECT itemId FROM synced_catalog_search WHERE synced_catalog_search MATCH :matchQuery")
    suspend fun searchItemIds(matchQuery: String): List<Long>
}