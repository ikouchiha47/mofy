package com.mofy.app.data.tmdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GenreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(genres: List<GenreEntity>)

    @Query("SELECT * FROM genres")
    suspend fun getAll(): List<GenreEntity>

    @Query("SELECT COUNT(*) FROM genres")
    suspend fun count(): Int
}
