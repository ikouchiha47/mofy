package com.mofy.app.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LibraryItem)

    @Update
    suspend fun update(item: LibraryItem)

    @Query("SELECT * FROM library_items ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM library_items WHERE `key` = :key")
    suspend fun getByKey(key: String): LibraryItem?

    @Query("SELECT * FROM library_items WHERE `key` = :key")
    fun observeByKey(key: String): Flow<LibraryItem?>

    // IGNORE, not REPLACE - the unique index on (libraryItemKey, infoHash)
    // is what makes re-confirming the same torrent a no-op instead of a
    // duplicate row, while different releases still both get inserted.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownload(download: LibraryDownload)

    @Query("SELECT * FROM library_downloads WHERE libraryItemKey = :key ORDER BY addedAtEpochMillis DESC")
    fun observeDownloads(key: String): Flow<List<LibraryDownload>>
}
