package com.mofy.app.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

enum class ResourceType { MAGNET, MANUAL }

/**
 * One or more downloadable resources attached to a library item - like
 * adding trackers/quality options to an existing torrent entry rather than
 * creating a duplicate. dedupeKey (infoHash for magnets, a hash of
 * name+uri for manual entries) is unique per libraryItemKey so re-adding
 * the exact same thing doesn't duplicate, while different releases (720p
 * vs 1080p) of the same item both stay. See ADR 0005 - infoHash alone
 * can't be the dedup key because it's null for manual entries, and SQLite
 * treats every NULL as distinct.
 */
@Entity(
    tableName = "library_downloads",
    indices = [Index(value = ["libraryItemKey", "dedupeKey"], unique = true)],
)
data class LibraryDownload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryItemKey: String,
    val resourceType: String, // ResourceType.name
    val name: String?,
    val uri: String,
    val infoHash: String?,
    val dedupeKey: String,
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

fun downloadDedupeKey(infoHash: String?, name: String?, uri: String): String {
    if (infoHash != null) return infoHash
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${name.orEmpty()}:$uri".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
