package com.mofy.app.data.sites

import com.mofy.app.data.tmdb.MediaType

data class TorrentSite(
    val name: String,
    val baseUrl: String,
    val category: MediaType,
    val titleSelector: String?,
)

object SiteCatalog {
    val sites = listOf(
        TorrentSite(
            name = "YTS",
            baseUrl = "https://yts.vg/",
            category = MediaType.MOVIE,
            titleSelector = ".right-details-box .title-year h1",
        ),
    )

    fun byCategory(category: MediaType): List<TorrentSite> = sites.filter { it.category == category }

    fun byName(name: String): TorrentSite? = sites.find { it.name == name }
}
