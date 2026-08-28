package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_progress",
    foreignKeys = [ForeignKey(
        entity = LibraryItem::class,
        parentColumns = ["id"],
        childColumns = ["libraryItemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("libraryItemId")],
)
data class WatchProgress(
    @PrimaryKey val libraryItemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long = System.currentTimeMillis(),
)
