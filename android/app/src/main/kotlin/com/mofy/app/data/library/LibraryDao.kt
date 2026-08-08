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
    suspend fun insertRaw(item: LibraryItem)

    @Update
    suspend fun updateRaw(item: LibraryItem)

    /** Insert/replace plus keeping the FTS5 search index in sync - see LibrarySearchEntity. */
    @Transaction
    suspend fun upsert(item: LibraryItem) {
        insertRaw(item)
        reindexSearch(item)
    }

    /** Update plus keeping the FTS5 search index in sync. */
    @Transaction
    suspend fun update(item: LibraryItem) {
        updateRaw(item)
        reindexSearch(item)
    }

    @Query("SELECT * FROM library_items ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<LibraryItem>>

    /** One-shot fetch (not a Flow) - used to backfill the search index for items saved before it existed. */
    @Query("SELECT * FROM library_items")
    suspend fun getAllOnce(): List<LibraryItem>

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
        deleteSearchIndex(id)
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

    // --- FTS5 search index (see LibrarySearchEntity, ADR 0002) ---

    @Insert
    suspend fun insertSearchEntry(entry: LibrarySearchEntity)

    @Query("DELETE FROM library_search WHERE itemId = :itemId")
    suspend fun deleteSearchIndex(itemId: String)

    @Transaction
    suspend fun reindexSearch(item: LibraryItem) {
        deleteSearchIndex(item.id)
        insertSearchEntry(
            LibrarySearchEntity(
                itemId = item.id,
                title = item.title,
                overview = item.overview,
                originalTitle = item.originalTitle.orEmpty(),
                romanizedOriginalTitle = item.romanizedOriginalTitle.orEmpty(),
            ),
        )
        SpellfixIndex.indexWords(
            tokenize(item.title) +
                tokenize(item.overview) +
                tokenize(item.originalTitle.orEmpty()) +
                tokenize(item.romanizedOriginalTitle.orEmpty()),
        )
    }

    @Query("SELECT itemId FROM library_search WHERE library_search MATCH :matchQuery")
    suspend fun searchItemIds(matchQuery: String): List<String>

    /**
     * FTS prefix search first (cheap, exact/prefix matches); if a token
     * gets no hits at all, spellfix1 suggests the closest indexed word
     * (e.g. "hui" -> "huo") and the query is retried with corrections
     * substituted in - see SpellfixIndex.
     */
    suspend fun searchLibrary(rawQuery: String): List<String> {
        val tokens = tokenize(rawQuery)
        if (tokens.isEmpty()) return emptyList()

        val direct = searchItemIds(matchQueryFromTokens(tokens) ?: return emptyList())
        if (direct.isNotEmpty()) return direct

        val corrected = tokens.map { token -> SpellfixIndex.suggest(token).firstOrNull() ?: token }
        if (corrected == tokens) return emptyList()
        return searchItemIds(matchQueryFromTokens(corrected) ?: return emptyList())
    }
}
