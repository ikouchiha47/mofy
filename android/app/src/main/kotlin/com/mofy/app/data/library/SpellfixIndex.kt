package com.mofy.app.data.library

import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Fuzzy-match fallback for typo'd search tokens - a companion to FTS, not
 * a replacement. Backed by SQLite's spellfix1 extension (see
 * docs/research/native-sqlite-extensions-android.md); Room has no
 * annotation for spellfix1's virtual table module (only @Fts3/@Fts4), so
 * this talks to it via raw SQL through Room's connection API instead of a
 * generated Dao. Uses the BundledSQLiteDriver connection API
 * (useWriterConnection/usePrepared), not SupportSQLiteDatabase - the
 * driver swap to BundledSQLiteDriver means that framework-driver API
 * isn't available at all anymore.
 *
 * App-managed, not trigger-managed - matches library_search's own
 * app-side reindexSearch pattern (see LibraryDao), and the dedupe/
 * normalization logic here (lowercasing, minimum token length, skipping
 * already-indexed words) is far easier to express in Kotlin than in a
 * SQL trigger body.
 */
object SpellfixIndex {
    private const val MIN_WORD_LENGTH = 3

    private var db: RoomDatabase? = null

    /** Schema setup is one-time and synchronous at DB-open time - see AppDatabase.get(). */
    fun attach(database: RoomDatabase) {
        db = database
        runBlocking(Dispatchers.IO) {
            database.useWriterConnection { connection ->
                connection.usePrepared("CREATE VIRTUAL TABLE IF NOT EXISTS search_vocab USING spellfix1") { it.step() }
            }
        }
    }

    /** Adds a library item's words to the fuzzy-match vocabulary, skipping ones already indexed. */
    suspend fun indexWords(words: Collection<String>) {
        val database = db ?: return
        database.useWriterConnection { connection ->
            for (raw in words) {
                val word = raw.lowercase()
                if (word.length < MIN_WORD_LENGTH) continue
                val alreadyIndexed = connection.usePrepared(
                    "SELECT 1 FROM search_vocab WHERE word = ? LIMIT 1",
                ) { stmt ->
                    stmt.bindText(1, word)
                    stmt.step()
                }
                if (!alreadyIndexed) {
                    connection.usePrepared("INSERT INTO search_vocab(word) VALUES (?)") { stmt ->
                        stmt.bindText(1, word)
                        stmt.step()
                    }
                }
            }
        }
    }

    /** Closest indexed word(s) to a mistyped token, nearest first; empty if nothing close enough. */
    suspend fun suggest(token: String, topN: Int = 3): List<String> {
        val database = db ?: return emptyList()
        if (token.length < MIN_WORD_LENGTH) return emptyList()
        return database.useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT word FROM search_vocab WHERE word MATCH ? AND top = ?",
            ) { stmt ->
                stmt.bindText(1, token.lowercase())
                stmt.bindLong(2, topN.toLong())
                val results = mutableListOf<String>()
                while (stmt.step()) results.add(stmt.getText(0))
                results
            }
        }
    }
}
