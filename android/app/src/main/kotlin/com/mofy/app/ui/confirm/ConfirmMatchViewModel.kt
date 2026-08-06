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
    // Single-select: which result the captured magnet actually is - one
    // magnet can only ever be one thing.
    val selected: MediaResult? = null,
    // Multi-select: results checked to "Save to Library" - independent of
    // the download selection, since you might want to save a few candidates
    // (e.g. unsure which sequel this is) without downloading any of them.
    val selectedForLibrary: Set<Int> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

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
                    selected = result.data.firstOrNull(),
                    isLoading = false,
                )
                is TmdbResult.Failure -> _uiState.value.copy(
                    results = emptyList(),
                    isLoading = false,
                    errorMessage = "Search failed: ${result.error}",
                )
            }
        }
    }

    fun selectResult(result: MediaResult) {
        _uiState.value = _uiState.value.copy(selected = result)
    }

    fun toggleLibrarySelection(result: MediaResult) {
        val current = _uiState.value.selectedForLibrary
        val updated = if (result.id in current) current - result.id else current + result.id
        _uiState.value = _uiState.value.copy(selectedForLibrary = updated)
    }
}
