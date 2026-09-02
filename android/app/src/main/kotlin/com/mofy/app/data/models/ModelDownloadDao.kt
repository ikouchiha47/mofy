package com.mofy.app.data.models

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ModelDownloadState)

    @Query("SELECT * FROM model_download_state WHERE modelKey = :modelKey")
    suspend fun get(modelKey: String): ModelDownloadState?

    @Query("SELECT * FROM model_download_state ORDER BY modelKey ASC")
    fun observeAll(): Flow<List<ModelDownloadState>>

    @Query("SELECT * FROM model_download_state WHERE status = :status")
    suspend fun findByStatus(status: String): List<ModelDownloadState>
}
