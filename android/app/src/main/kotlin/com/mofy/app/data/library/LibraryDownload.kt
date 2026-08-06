package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ResourceType { MAGNET }

/**
 * One or more downloadable resources attached to a library item - like
 * adding trackers/quality options to an existing torrent entry rather than
 * creating a duplicate. infoHash (from the magnet's btih) is unique per
 * libraryItemKey so re-confirming the exact same torrent doesn't duplicate,
 * while different releases (720p vs 1080p) of the same item both stay.
 */
@Entity(
    tableName = "library_downloads",
    indices = [Index(value = ["libraryItemKey", "infoHash"], unique = true)],
)
data class LibraryDownload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryItemKey: String,
    val resourceType: String, // ResourceType.name
    val uri: String,
    val infoHash: String?,
    val addedAtEpochMillis: Long,
)

fun magnetInfoHash(magnetUri: String): String? {
    val query = magnetUri.substringAfter('?', missingDelimiterValue = "")
    return query.split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == "xt" }
        ?.get(1)
        ?.substringAfterLast(':')
        ?.lowercase()
}
