package com.mofy.app.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.mofy.app.data.catalog.CatalogPosterCache
import com.mofy.app.data.catalog.CatalogPosterCacheDao
import com.mofy.app.data.catalog.SyncedCatalogItem
import com.mofy.app.data.catalog.SyncedCatalogSearchEntity
import com.mofy.app.data.catalog.SyncedCatalogVecDao
import com.mofy.app.data.models.ModelDownloadDao
import com.mofy.app.data.models.ModelDownloadState
import com.mofy.app.data.sites.SiteDao
import com.mofy.app.data.sites.TorrentSiteEntity
import com.mofy.app.data.tmdb.GenreDao
import com.mofy.app.data.tmdb.GenreEntity
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        LibraryItem::class,
        LibraryDownload::class,
        LibraryLink::class,
        LibrarySearchEntity::class,
        GenreEntity::class,
        TorrentSiteEntity::class,
        WatchProgress::class,
        CatalogPosterCache::class,
        SyncedCatalogItem::class,
        SyncedCatalogSearchEntity::class,
        ModelDownloadState::class,
    ],
    // 15: imdbId index on library_items (schema hash fix).
    // 16: synced_catalog_items, synced_catalog_search (FTS4), synced_catalog_vec (vec0) for TMDB new-releases sync (ADR 0009).
    // 17: model_download_state for foreground-service model downloads (ADR 0010 task 1).
    version = 17,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun genreDao(): GenreDao
    abstract fun siteDao(): SiteDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun catalogPosterCacheDao(): CatalogPosterCacheDao
    abstract fun syncedCatalogDao(): com.mofy.app.data.catalog.SyncedCatalogDao
    abstract fun syncedCatalogSearchDao(): com.mofy.app.data.catalog.SyncedCatalogSearchDao
    abstract fun modelDownloadDao(): ModelDownloadDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "mofy.db",
            )
                // Pre-release dev app, no real user data to preserve yet -
                // simplest path across schema changes.
                .fallbackToDestructiveMigration(true)
                // Custom driver (not the default framework one) is required to
                // load native extensions - see
                // docs/research/native-sqlite-extensions-android.md. sqlite-vec's
                // prebuilt .so lands in jniLibs and gets extracted to
                // nativeLibraryDir at install time; "sqlite3_vec_init" is the
                // extension's documented entry point symbol. spellfix1 is
                // self-built from SQLite's own source via
                // github.com/ikouchiha47/spellfix-builds (no trustworthy
                // prebuilt exists for it) - entry point "sqlite3_spellfix_init"
                // per ext/misc/spellfix.c's own init macro.
                .setDriver(
                    BundledSQLiteDriver().apply {
                        val nativeLibraryDir = context.applicationContext.applicationInfo.nativeLibraryDir
                        addExtension("$nativeLibraryDir/libvec0", "sqlite3_vec_init")
                        addExtension("$nativeLibraryDir/libspellfix", "sqlite3_spellfix_init")
                    },
                )
                .addMigrations(Migrations.MIGRATION_15_16, Migrations.MIGRATION_16_17)
                // synced_catalog_vec isn't a Room @Entity (vec0's float[768]
                // column syntax has no Room-representable form), so it only
                // ever gets created by MIGRATION_15_16's execSQL - but a
                // fresh install never runs any Migration at all, Room just
                // stamps out the schema straight from @Entity classes.
                // Confirmed on a real device via logcat after a reinstall:
                // "no such table: synced_catalog_vec" on every sync.
                // onCreate() is Room's own hook for exactly this gap - fires
                // only when the database is genuinely new, reuses the same
                // CREATE_TABLE_SQL constant the migration already uses.
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onCreate(connection: SQLiteConnection) {
                            connection.execSQL(SyncedCatalogVecDao.CREATE_TABLE_SQL)
                        }
                    },
                )
                .setQueryCoroutineContext(Dispatchers.IO)
                .build().also {
                    instance = it
                    SpellfixIndex.attach(it)
                }
        }
    }
}
