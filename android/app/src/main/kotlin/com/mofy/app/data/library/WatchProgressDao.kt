package com.mofy.app.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w185"

data class WatchProgressWithItem(
    val libraryItemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    val title: String,
    val posterPath: String?,
    val localPosterUri: String?,
    val posterSource: String,
    val year: String?,
) {
    val posterUrl: String?
        get() = when (posterSource) {
            "UPLOADED" -> localPosterUri
            "TMDB" -> posterPath?.let { "$TMDB_IMAGE_BASE_URL$it" }
            else -> null
        }
}

@Dao
interface WatchProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: WatchProgress)

    @Query("""
        SELECT wp.libraryItemId, wp.positionMs, wp.durationMs, wp.lastWatchedAt,
               li.title, li.posterPath, li.localPosterUri, li.posterSource, li.year
        FROM watch_progress wp
        JOIN library_items li ON li.id = wp.libraryItemId
        WHERE wp.positionMs > 0
          AND wp.durationMs > 0
          AND CAST(wp.positionMs AS REAL) / wp.durationMs < 0.95
        ORDER BY wp.lastWatchedAt DESC
        LIMIT 20
    """)
    fun observeInProgress(): Flow<List<WatchProgressWithItem>>

    @Query("SELECT * FROM watch_progress WHERE libraryItemId = :id")
    suspend fun get(id: Long): WatchProgress?

    @Query("DELETE FROM watch_progress WHERE libraryItemId = :id")
    suspend fun delete(id: Long)
}
