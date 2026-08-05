package com.mofy.app.ui.search

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

data class SearchUiState(
    val query: String = "",
    val mediaType: MediaType = MediaType.MOVIE,
    val results: List<MediaResult> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class SearchViewModel(
    private val repository: TmdbRepository = TmdbRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        debounceSearch()
    }

    fun onMediaTypeChanged(mediaType: MediaType) {
        _uiState.value = _uiState.value.copy(mediaType = mediaType)
        debounceSearch()
    }

    private fun debounceSearch() {
        searchJob?.cancel()
        val query = _uiState.value.query
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), errorMessage = null, isLoading = false)
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            runSearch(query, _uiState.value.mediaType)
        }
    }

    private suspend fun runSearch(query: String, mediaType: MediaType) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        val result = when (mediaType) {
            MediaType.MOVIE -> repository.searchMovies(query)
            MediaType.TV -> repository.searchTv(query)
        }
        _uiState.value = when (result) {
            is TmdbResult.Success -> _uiState.value.copy(results = result.data, isLoading = false)
            is TmdbResult.Failure -> _uiState.value.copy(
                results = emptyList(),
                isLoading = false,
                errorMessage = "Search failed: ${result.error}",
            )
        }
    }
}
