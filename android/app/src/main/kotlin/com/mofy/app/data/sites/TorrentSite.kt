package com.mofy.app.data.sites

import java.net.URLEncoder
import com.mofy.app.data.tmdb.MediaType

data class TorrentSite(
    val name: String,
    val baseUrl: String,
    val category: MediaType,
    val titleSelector: String?,
    // "%s" placeholder for the URL-encoded query. Loaded in the same WebView
    // as normal browsing (not a raw HTTP request) so the site's own session
    // cookies apply automatically - see docs/adrs (search-from-detail flow).
    val searchUrlTemplate: String? = null,
) {
    fun searchUrl(query: String): String? =
        searchUrlTemplate?.replace("%s", URLEncoder.encode(query, "UTF-8"))
}

object SiteCatalog {
    val sites = listOf(
        TorrentSite(
            name = "YTS",
            baseUrl = "https://yts.vg/",
            category = MediaType.MOVIE,
            titleSelector = ".right-details-box .title-year h1",
            searchUrlTemplate = "https://yts.vg/browse/search/%s/all/all/all/all/all/latest",
        ),
    )

    fun byCategory(category: MediaType): List<TorrentSite> = sites.filter { it.category == category }

    fun byName(name: String): TorrentSite? = sites.find { it.name == name }
}
