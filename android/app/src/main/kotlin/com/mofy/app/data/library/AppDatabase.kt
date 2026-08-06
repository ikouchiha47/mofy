package com.mofy.app.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LibraryItem::class, LibraryDownload::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

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
