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
    val selected: MediaResult? = null,
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
}
