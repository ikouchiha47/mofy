package com.mofy.app.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mofy.app.data.sites.SiteDao
import com.mofy.app.data.sites.TorrentSiteEntity
import com.mofy.app.data.tmdb.GenreDao
import com.mofy.app.data.tmdb.GenreEntity

@Database(
    entities = [
        LibraryItem::class,
        LibraryDownload::class,
        LibraryLink::class,
        GenreEntity::class,
        TorrentSiteEntity::class,
    ],
    version = 6,
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
                .build().also { instance = it }
        }
    }
}
