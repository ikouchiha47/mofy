package com.mofy.app.data.catalog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "catalog_poster_cache")
data class CatalogPosterCache(
    @PrimaryKey val tconst: String,
    val posterPath: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Dao
interface CatalogPosterCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<CatalogPosterCache>)

    @Query("SELECT tconst FROM catalog_poster_cache WHERE tconst IN (:tconsts)")
    suspend fun getCachedTconsts(tconsts: List<String>): List<String>

    @Query("SELECT tconst, posterPath FROM catalog_poster_cache WHERE tconst IN (:tconsts) AND posterPath IS NOT NULL")
    suspend fun getPosterPaths(tconsts: List<String>): List<TconstPosterPath>
}

data class TconstPosterPath(val tconst: String, val posterPath: String)
