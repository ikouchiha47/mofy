package com.mofy.app.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun getById(id: String): LibraryItem?

    @Query("SELECT * FROM library_items WHERE id = :id")
    fun observeById(id: String): Flow<LibraryItem?>

    @Query("SELECT * FROM library_items WHERE tmdbId = :tmdbId AND mediaType = :mediaType LIMIT 1")
    suspend fun getByTmdbMatch(tmdbId: Int, mediaType: String): LibraryItem?

    /**
     * Merge-on-conflict, not blind REPLACE - see ADR 0005. If a row already
     * exists for (tmdbId, mediaType), update it in place (same id) so any
     * attached library_downloads/library_links stay valid; otherwise insert
     * fresh. Also how a manual entry gets matched to TMDB later - call this
     * with the manual item's existing id copied onto the incoming row.
     */
    @Transaction
    suspend fun saveConfirmedMatch(incoming: LibraryItem) {
        val existing = incoming.tmdbId?.let { tmdbId ->
            incoming.mediaType?.let { mediaType -> getByTmdbMatch(tmdbId, mediaType) }
        }
        if (existing != null) {
            update(incoming.copy(id = existing.id, addedAtEpochMillis = existing.addedAtEpochMillis))
        } else {
            upsert(incoming)
        }
    }

    @Transaction
    suspend fun deleteLibraryItem(id: String) {
        deleteDownloadsFor(id)
        deleteLinksFor(id)
        deleteItem(id)
    }

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("DELETE FROM library_downloads WHERE libraryItemKey = :id")
    suspend fun deleteDownloadsFor(id: String)

    @Query("DELETE FROM library_links WHERE libraryItemKey = :id")
    suspend fun deleteLinksFor(id: String)

    // IGNORE, not REPLACE - the unique index on (libraryItemKey, dedupeKey)
    // is what makes re-adding the exact same thing a no-op instead of a
    // duplicate row, while different releases still both get inserted.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownload(download: LibraryDownload)

    @Query("SELECT * FROM library_downloads WHERE libraryItemKey = :id ORDER BY addedAtEpochMillis DESC")
    fun observeDownloads(id: String): Flow<List<LibraryDownload>>

    @Insert
    suspend fun insertLink(link: LibraryLink): Long

    @Query("SELECT * FROM library_links WHERE libraryItemKey = :id ORDER BY linkedAtEpochMillis DESC")
    fun observeLinks(id: String): Flow<List<LibraryLink>>

    @Query("SELECT * FROM library_links WHERE libraryItemKey = :id AND isActive = 1 LIMIT 1")
    fun observeActiveLink(id: String): Flow<LibraryLink?>

    @Query("UPDATE library_links SET isActive = 0 WHERE libraryItemKey = :id")
    suspend fun deactivateAllLinks(id: String)

    @Query("UPDATE library_links SET isActive = 1 WHERE id = :linkId")
    suspend fun activateLink(linkId: Long)

    /** Only one link is ever active per item - see LibraryLink. */
    @Transaction
    suspend fun setActiveLink(id: String, linkId: Long) {
        deactivateAllLinks(id)
        activateLink(linkId)
    }

    /** Add a new link and make it the active one in the same transaction. */
    @Transaction
    suspend fun addAndActivateLink(link: LibraryLink) {
        val linkId = insertLink(link)
        setActiveLink(link.libraryItemKey, linkId)
    }
}
