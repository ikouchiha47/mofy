package com.mofy.app.data.catalog

import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection

/**
 * Not a Room @Dao - Room can't model vec0's typed virtual-table columns.
 * Runs against AppDatabase's own connection via useWriterConnection/
 * useReaderConnection + usePrepared (the vec0 extension is already loaded
 * on AppDatabase's driver - see AppDatabase.kt's addExtension calls);
 * deliberately NOT a second SQLite connection.
 *
 * NOT SupportSQLiteOpenHelper/SupportSQLiteDatabase - AppDatabase is built
 * with .setDriver(BundledSQLiteDriver()), the new androidx.sqlite driver
 * API, which doesn't expose that legacy accessor at all
 * (RoomDatabase.openHelper throws IllegalStateException for a driver-based
 * database - confirmed by an actual on-device test failure, not assumed).
 * SpellfixIndex.kt in this same codebase already established the correct
 * pattern for raw SQL against this driver-based AppDatabase - mirrored
 * here rather than reinventing it.
 *
 * SQL/blob encoding mirrors VecDatabase.knn() (toBlob() copied exactly) -
 * that class also uses BundledSQLiteDriver's prepare/bindBlob/step API,
 * just via its own separately-opened connection to catalog_vec.db rather
 * than through Room's connection pool for mofy.db.
 */
class SyncedCatalogVecDao(private val database: RoomDatabase) {

    companion object {
        /**
         * Single source of truth for synced_catalog_vec's DDL - used by
         * Migrations.kt (real app databases, via SQLiteConnection.execSQL)
         * and by test setup (Room.inMemoryDatabaseBuilder databases, via
         * RoomDatabase.useWriterConnection). An in-memory database created
         * directly at the latest version never runs MIGRATION_15_16 (there's
         * no prior version to migrate from - Room creates the schema from
         * entity annotations instead), so this non-Room virtual table would
         * silently never exist in any such test without creating it here
         * too - confirmed by an actual "no such table: synced_catalog_vec"
         * failure, not assumed.
         */
        // float[256], not 768 - matches catalog_vec's MRL-truncated
        // dimension (see OnDeviceEmbedder's EMBEDDING_DIM) so the two vec
        // tables can eventually be queried/merged together (ADR 0009 task
        // 5 in project memory).
        const val CREATE_TABLE_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS synced_catalog_vec USING vec0(embedding float[256])"
    }

    suspend fun insert(itemId: Long, embedding: FloatArray) {
        database.useWriterConnection { connection ->
            connection.usePrepared(
                "INSERT INTO synced_catalog_vec(rowid, embedding) VALUES (?, ?)",
            ) { stmt ->
                stmt.bindLong(1, itemId)
                stmt.bindBlob(2, embedding.toBlob())
                stmt.step()
            }
        }
    }

    suspend fun knn(embedding: FloatArray, k: Int): List<Long> = database.useReaderConnection { connection ->
        connection.usePrepared(
            "WITH knn AS (" +
                "SELECT rowid, distance FROM synced_catalog_vec WHERE embedding MATCH ? AND k = ?" +
                ") " +
                "SELECT rowid FROM knn ORDER BY knn.distance",
        ) { stmt ->
            stmt.bindBlob(1, embedding.toBlob())
            stmt.bindLong(2, k.toLong())
            val results = mutableListOf<Long>()
            while (stmt.step()) results += stmt.getLong(0)
            results
        }
    }

    private fun FloatArray.toBlob(): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return buf.array()
    }
}
