package com.mofy.app.ui.watchtogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.watchtogether.SessionState
import com.mofy.app.watchtogether.WatchTogetherSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Carries the active [WatchTogetherSession] (plus the [LibraryItem] it's
 * bound to) across Create/Join -> Lobby -> Player navigation - same
 * app-scoped remember() pattern as BrowseSessionViewModel. A session must
 * survive screen transitions and stay visible to Detail's SessionPill and
 * the app-wide LiveSessionBar regardless of which tab is on screen.
 */
class WatchTogetherSessionViewModel : ViewModel() {

    private val _session = MutableStateFlow<WatchTogetherSession?>(null)
    val session: StateFlow<WatchTogetherSession?> = _session.asStateFlow()

    private val _activeItem = MutableStateFlow<LibraryItem?>(null)
    val activeItem: StateFlow<LibraryItem?> = _activeItem.asStateFlow()

    /** Live [SessionState] of whatever session is active - for LiveSessionBar/SessionPill. */
    val sessionState: StateFlow<SessionState?> = _session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setActive(session: WatchTogetherSession, item: LibraryItem?) {
        _session.value = session
        _activeItem.value = item
    }

    fun clear() {
        _session.value?.end()
        _session.value = null
        _activeItem.value = null
    }
}
