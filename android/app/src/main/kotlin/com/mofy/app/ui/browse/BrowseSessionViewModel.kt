package com.mofy.app.ui.browse

import android.os.Bundle
import androidx.lifecycle.ViewModel
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.data.tmdb.MediaType
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries state across the category -> site list -> webview -> confirm-match
 * screens. The category is fixed the moment a site is picked, so the confirm
 * step never has to guess movie vs tv - see docs/phases/02.
 */
class BrowseSessionViewModel : ViewModel() {

    private val _selectedCategory = MutableStateFlow<MediaType?>(null)
    val selectedCategory: StateFlow<MediaType?> = _selectedCategory.asStateFlow()

    private val _selectedSite = MutableStateFlow<TorrentSite?>(null)
    val selectedSite: StateFlow<TorrentSite?> = _selectedSite.asStateFlow()

    private val _extractedTitle = MutableStateFlow<String?>(null)
    val extractedTitle: StateFlow<String?> = _extractedTitle.asStateFlow()

    private val _pendingMagnetUri = MutableStateFlow<String?>(null)
    val pendingMagnetUri: StateFlow<String?> = _pendingMagnetUri.asStateFlow()

    // WebView navigation history (saveState/restoreState) so returning from
    // Confirm Match doesn't reload the site from scratch - Compose Navigation
    // disposes the WebView composable when it's off the back stack.
    var webViewState: Bundle? = null

    fun selectCategory(category: MediaType) {
        _selectedCategory.value = category
    }

    fun selectSite(site: TorrentSite) {
        _selectedSite.value = site
        _extractedTitle.value = null
        webViewState = null
    }

    fun onTitleExtracted(title: String) {
        if (title.isNotBlank()) _extractedTitle.value = title
    }

    /**
     * Magnet URIs carry a standard `dn=` (display name) param - the actual
     * torrent's name, guaranteed present, tied to the exact thing being
     * downloaded regardless of what page it was tapped from. That's more
     * reliable than page-scraped title, which only matches detail pages and
     * can be stale/wrong when magnets are tapped from a listing/grid page.
     * Falls back to the page-scraped title only if `dn` is missing.
     */
    fun onMagnetTapped(magnetUri: String) {
        _pendingMagnetUri.value = magnetUri
        parseMagnetDisplayName(magnetUri)?.let { _extractedTitle.value = it }
    }

    fun clearAfterConfirm() {
        _pendingMagnetUri.value = null
        _extractedTitle.value = null
        webViewState = null
    }
}

private fun parseMagnetDisplayName(magnetUri: String): String? {
    val query = magnetUri.substringAfter('?', missingDelimiterValue = "")
    val dn = query.split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == "dn" }
        ?.get(1)
        ?: return null
    return runCatching { URLDecoder.decode(dn, "UTF-8") }.getOrNull()?.replace('+', ' ')?.trim()?.takeIf { it.isNotBlank() }
}
