package com.mofy.app.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LibraryItem)

    @Query("SELECT * FROM library_items ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<LibraryItem>>
}
