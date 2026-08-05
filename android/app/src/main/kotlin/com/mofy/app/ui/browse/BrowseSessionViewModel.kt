package com.mofy.app.ui.browse

import androidx.lifecycle.ViewModel
import com.mofy.app.data.sites.TorrentSite
import com.mofy.app.data.tmdb.MediaType
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

    fun selectCategory(category: MediaType) {
        _selectedCategory.value = category
    }

    fun selectSite(site: TorrentSite) {
        _selectedSite.value = site
        _extractedTitle.value = null
    }

    fun onTitleExtracted(title: String) {
        if (title.isNotBlank()) _extractedTitle.value = title
    }

    fun onMagnetTapped(magnetUri: String) {
        _pendingMagnetUri.value = magnetUri
    }

    fun clearAfterConfirm() {
        _pendingMagnetUri.value = null
        _extractedTitle.value = null
    }
}
