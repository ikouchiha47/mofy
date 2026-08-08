package com.mofy.app.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun genreDao(): GenreDao
    abstract fun siteDao(): SiteDao

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
                .setQueryCoroutineContext(Dispatchers.IO)
                .build().also {
                    instance = it
                    SpellfixIndex.attach(it)
                }
        }
    }
}
