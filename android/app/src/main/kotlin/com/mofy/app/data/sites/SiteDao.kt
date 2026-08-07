package com.mofy.app.data.sites

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: TorrentSiteEntity)

    @Query("SELECT * FROM sites ORDER BY name")
    fun observeAll(): Flow<List<TorrentSiteEntity>>

    @Query("SELECT * FROM sites WHERE name = :name")
    suspend fun getByName(name: String): TorrentSiteEntity?

    @Query("SELECT COUNT(*) FROM sites")
    suspend fun count(): Int

    @Query("DELETE FROM sites WHERE name = :name")
    suspend fun delete(name: String)
}
