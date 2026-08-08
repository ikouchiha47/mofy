package com.mofy.app.data.catalog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Bundled, read-only IMDb catalog (ml/data/catalog.db, ~39MB) - copied out
 * of assets to internal storage on first run (SQLite can't open a database
 * directly from inside an APK/assets), then opened read-only. Plain
 * framework SQLiteDatabase, not Room: this is static shipped data with no
 * migrations, writes, or entity mapping needed, and its FTS4 table doesn't
 * need BundledSQLiteDriver/native extensions the way library_search's
 * spellfix1 fallback does - FTS4 is a built-in SQLite feature.
 */
object CatalogDatabase {
    private const val ASSET_NAME = "catalog.db"

    @Volatile private var instance: SQLiteDatabase? = null

    fun get(context: Context): SQLiteDatabase = instance ?: synchronized(this) {
        instance ?: run {
            val dest = File(context.getDatabasePath(ASSET_NAME).path)
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                context.assets.open(ASSET_NAME).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            SQLiteDatabase.openDatabase(dest.path, null, SQLiteDatabase.OPEN_READONLY).also { instance = it }
        }
    }
}
