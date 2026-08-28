package com.mofy.app.data.catalog

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * Read-only wrapper around catalog_vec.db (sqlite-vec virtual table).
 * Copied from assets on first run, then opened via BundledSQLiteDriver
 * so the vec0 extension is available for KNN queries.
 */
object VecDatabase {
    private const val ASSET_NAME = "catalog_vec.db"

    @Volatile private var driver: BundledSQLiteDriver? = null

    fun get(context: Context): BundledSQLiteDriver = driver ?: synchronized(this) {
        driver ?: build(context).also { driver = it }
    }

    private fun build(context: Context): BundledSQLiteDriver {
        val dest = File(context.getDatabasePath(ASSET_NAME).path)
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { it.copyTo(dest.outputStream()) }
        }
        val nativeLibDir = context.applicationContext.applicationInfo.nativeLibraryDir
        return BundledSQLiteDriver().apply {
            addExtension("$nativeLibDir/libvec0", "sqlite3_vec_init")
        }
    }

    /** KNN query: returns (tconst, title) pairs ranked by L2 distance to [embedding]. */
    fun knn(context: Context, embedding: FloatArray, k: Int): List<Pair<String, String>> {
        val dest = File(context.getDatabasePath(ASSET_NAME).path)
        val db = get(context).open(dest.path)
        return try {
            val blob = embedding.toBlob()
            val results = mutableListOf<Pair<String, String>>()
            // vec0 KNN syntax: WHERE embedding MATCH <blob> ORDER BY distance LIMIT k
            val stmt = db.prepare(
                "WITH knn AS (" +
                    "SELECT rowid, distance FROM catalog_vec WHERE embedding MATCH ? AND k = ?" +
                    ") " +
                    "SELECT m.tconst, m.title FROM knn " +
                    "JOIN catalog_meta m ON m.rowid = knn.rowid " +
                    "ORDER BY knn.distance",
            )
            try {
                stmt.bindBlob(1, blob)
                stmt.bindInt(2, k)
                while (stmt.step()) results += stmt.getText(0) to stmt.getText(1)
            } finally {
                stmt.close()
            }
            results
        } finally {
            db.close()
        }
    }

    private fun FloatArray.toBlob(): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return buf.array()
    }
}
