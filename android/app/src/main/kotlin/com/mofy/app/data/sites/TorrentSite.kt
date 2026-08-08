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
    // true for sites whose search endpoint expects standard
    // application/x-www-form-urlencoded spaces ("+", e.g. TorrentSeeker's
    // Google Custom Search backend) - false (default) for sites that treat
    // the query as a URL path segment and need "%20" instead (YTS).
    val useFormEncodedSpaces: Boolean = false,
)

data class TorrentSite(
    val name: String,
    val baseUrl: String,
    val category: MediaType,
    val titleSelector: String?,
    val searchConfig: SiteSearchConfig? = null,
) {
    fun searchUrl(query: String): String? {
        val config = searchConfig ?: return null
        // URLEncoder implements application/x-www-form-urlencoded (space ->
        // "+"), not path-segment percent-encoding (space -> "%20") - sites
        // that treat the search term as a URL path segment (YTS) break on
        // "+", so it's swapped for "%20" after encoding everything else,
        // unless the site explicitly wants form-encoded spaces (TorrentSeeker).
        val encoded = URLEncoder.encode(query, "UTF-8").let {
            if (config.useFormEncodedSpaces) it else it.replace("+", "%20")
        }
        val resolved = config.searchPath.replace("{query}", encoded)
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
        // Meta-search (Google Custom Search widget over "dozens of search
        // engines", not a single torrent index) - no fixed page structure
        // to extract a title from, so titleSelector is null same as EZTV.
        // Magnet capture still works: it's scheme-based
        // (shouldOverrideUrlLoading on magnet: URIs in TorrentWebViewScreen),
        // not tied to any specific site's markup.
        TorrentSite(
            name = "TorrentSeeker",
            baseUrl = "https://torrentseeker.com/",
            category = MediaType.MOVIE,
            titleSelector = null,
            searchConfig = SiteSearchConfig(
                method = HttpMethod.GET,
                searchPath = "/search.php?q={query}",
                useFormEncodedSpaces = true,
            ),
        ),
    )
}
