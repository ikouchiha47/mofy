package com.mofy.app.data.sites

import java.net.URLEncoder
import com.mofy.app.data.tmdb.MediaType

enum class HttpMethod { GET, POST }

/**
 * Search always happens inside the same WebView used for normal browsing
 * (not a raw HTTP client) so the site's own session/cookies apply
 * automatically - see docs/adrs (search-from-detail flow). GET is fully
 * wired via WebView.loadUrl(url, headers). POST is parked: WebView has no
 * clean way to send a POST with custom headers, and no provider needs it
 * yet - see TorrentWebViewScreen.buildSearchRequest for where it'd land.
 */
data class SiteSearchConfig(
    val method: HttpMethod = HttpMethod.GET,
    // "{query}" placeholder, URL-encoded on substitution. Absolute, or
    // relative to the site's baseUrl.
    val searchPath: String,
    val headers: Map<String, String> = emptyMap(),
)

data class TorrentSite(
    val name: String,
    val baseUrl: String,
    val category: MediaType,
    val titleSelector: String?,
    val searchConfig: SiteSearchConfig? = null,
) {
    fun searchUrl(query: String): String? {
        val path = searchConfig?.searchPath ?: return null
        val encoded = URLEncoder.encode(query, "UTF-8")
        val resolved = path.replace("{query}", encoded)
        return if (resolved.startsWith("http")) resolved else baseUrl.trimEnd('/') + "/" + resolved.trimStart('/')
    }
}

/** Seed data only - see SiteDao/MofyApplication. The DB is the runtime source of truth. */
object SiteCatalog {
    val defaultSites = listOf(
        TorrentSite(
            name = "YTS",
            baseUrl = "https://yts.vg/",
            category = MediaType.MOVIE,
            titleSelector = ".right-details-box .title-year h1",
            searchConfig = SiteSearchConfig(
                method = HttpMethod.GET,
                searchPath = "/browse/search/{query}/all/all/all/all/all/latest",
            ),
        ),
        TorrentSite(
            name = "EZTV",
            baseUrl = "https://eztv.proxyninja.org/home",
            category = MediaType.TV,
            titleSelector = null,
            searchConfig = SiteSearchConfig(
                method = HttpMethod.GET,
                searchPath = "https://eztv.proxyninja.org/search/?q1={query}&search=Search",
            ),
        ),
    )
}
