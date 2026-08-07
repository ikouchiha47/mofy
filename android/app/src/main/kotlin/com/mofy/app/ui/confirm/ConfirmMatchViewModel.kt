package com.mofy.app.ui.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mofy.app.data.tmdb.MediaResult
import com.mofy.app.data.tmdb.MediaType
import com.mofy.app.data.tmdb.TmdbRepository
import com.mofy.app.data.tmdb.TmdbResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConfirmMatchUiState(
    val results: List<MediaResult> = emptyList(),
    // Multi-select: results checked to "Save to Library" - independent of
    // which one the captured magnet actually is, since you might want to
    // save a few candidates (or unrelated titles you noticed) without any
    // of them being the magnet's match.
    val checkedIds: Set<Int> = emptySet(),
    // Single-select: which result the captured magnet actually is. Separate
    // from checkedIds on purpose - checking a box for "save this too" must
    // never accidentally make Confirm & Download fire against the wrong
    // title relative to the magnet you actually tapped.
    val magnetMatchId: Int? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // Distinguishes "hasn't searched yet" (manual-search screens start
    // empty) from "searched and found nothing" - only the latter should
    // show a "no matches" message.
    val hasSearched: Boolean = false,
) {
    val confirmTarget: MediaResult?
        get() = magnetMatchId?.let { id -> results.firstOrNull { it.id == id } }
}

/**
 * Phase 02's magnet-capture hand-off, closed the loop with Phase 01's TMDB
 * client. The media type is never re-guessed here - it's already locked from
 * whichever Browse category the site belonged to (see ADR 0003).
 */
class ConfirmMatchViewModel(
    private val repository: TmdbRepository = TmdbRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfirmMatchUiState())
    val uiState: StateFlow<ConfirmMatchUiState> = _uiState.asStateFlow()

    fun search(query: String, mediaType: MediaType) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = when (mediaType) {
                MediaType.MOVIE -> repository.searchMovies(query)
                MediaType.TV -> repository.searchTv(query)
            }
            _uiState.value = when (result) {
                is TmdbResult.Success -> _uiState.value.copy(
                    results = result.data,
                    // No default - the user must explicitly pick which
                    // result the magnet actually matches via the radio.
                    magnetMatchId = null,
                    isLoading = false,
                    hasSearched = true,
                )
                is TmdbResult.Failure -> _uiState.value.copy(
                    results = emptyList(),
                    isLoading = false,
                    errorMessage = "Search failed: ${result.error}",
                    hasSearched = true,
                )
            }
        }
    }

    fun toggleChecked(result: MediaResult) {
        val current = _uiState.value.checkedIds
        val updated = if (result.id in current) current - result.id else current + result.id
        _uiState.value = _uiState.value.copy(checkedIds = updated)
    }

    fun selectMagnetMatch(result: MediaResult) {
        _uiState.value = _uiState.value.copy(magnetMatchId = result.id)
    }
}
