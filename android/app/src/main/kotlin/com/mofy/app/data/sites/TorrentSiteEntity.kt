package com.mofy.app.data.sites

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mofy.app.data.tmdb.MediaType

/**
 * Persisted form of TorrentSite - user-editable via the Edit Site screen.
 * SiteCatalog.defaultSites seeds this table on first launch (MofyApplication)
 * so the app isn't empty before the user adds anything themselves.
 */
@Entity(tableName = "sites")
data class TorrentSiteEntity(
    @PrimaryKey val name: String,
    val baseUrl: String,
    val category: String, // MediaType.name
    val titleSelector: String?,
    val searchMethod: String, // HttpMethod.name
    val searchPath: String?,
    // "Header: value" per line - header names never contain a colon, so this
    // is unambiguous without pulling in a JSON TypeConverter for one field.
    val headersRaw: String,
)

fun TorrentSiteEntity.toTorrentSite(): TorrentSite = TorrentSite(
    name = name,
    baseUrl = baseUrl,
    category = MediaType.valueOf(category),
    titleSelector = titleSelector,
    searchConfig = searchPath?.let {
        SiteSearchConfig(
            method = HttpMethod.valueOf(searchMethod),
            searchPath = it,
            headers = parseHeaders(headersRaw),
        )
    },
)

fun TorrentSite.toEntity(): TorrentSiteEntity = TorrentSiteEntity(
    name = name,
    baseUrl = baseUrl,
    category = category.name,
    titleSelector = titleSelector,
    searchMethod = (searchConfig?.method ?: HttpMethod.GET).name,
    searchPath = searchConfig?.searchPath,
    headersRaw = formatHeaders(searchConfig?.headers ?: emptyMap()),
)

fun formatHeaders(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { (k, v) -> "$k: $v" }

fun parseHeaders(raw: String): Map<String, String> = raw.lineSequence()
    .mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        if (key.isEmpty()) null else key to value
    }
    .toMap()
