package com.mofy.app.watchtogether

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mofy.app.data.library.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One session this device currently participates in, plus the [LibraryItem] it's bound to. */
data class ActiveSession(
    val session: WatchTogetherSession,
    val item: LibraryItem?,
)

/**
 * Process-wide pool of every [WatchTogetherSession] this device currently
 * participates in (host or guest, capped at [SessionLimits.MAX_CONCURRENT_SESSIONS]),
 * independent of any screen/Activity lifecycle - same shape as
 * [com.mofy.app.watchtogether.webrtc.PeerConnectionFactoryHolder] (ADR 0011).
 *
 * Replaces `WatchTogetherSessionViewModel`, which extended Android's
 * `ViewModel` but was instantiated via `remember { WatchTogetherSessionViewModel() }`
 * rather than the framework's `viewModel()` factory - it got none of what a
 * real ViewModel is for (surviving configuration changes via a managed
 * store) and was really just a Compose-remembered object bounded by the
 * Activity's own composition. That mismatch was confirmed as a real bug on
 * a real device: nothing protected a live session's WebSocket/WebRTC
 * connection from Android's background execution limits once the app
 * itself (not just the current screen) was backgrounded - see
 * docs/adrs/0011-watch-together-session-lifecycle.md.
 */
object WatchTogetherSessionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null

    private val _sessions = MutableStateFlow<List<ActiveSession>>(emptyList())
    val sessions: StateFlow<List<ActiveSession>> = _sessions.asStateFlow()

    /** Called once from MofyApplication.onCreate() - same pattern as PeerConnectionFactoryHolder.init(). */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            _sessions.map { it.isNotEmpty() }.distinctUntilChanged().collect { hasSessions ->
                val ctx = appContext ?: return@collect
                val intent = Intent(ctx, WatchTogetherForegroundService::class.java)
                if (hasSessions) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(intent)
                    } else {
                        ctx.startService(intent)
                    }
                } else {
                    ctx.stopService(intent)
                }
            }
        }
    }

    /** Live [SessionState] of every active session - for a Listing screen / matching by itemHash. */
    val sessionStates: StateFlow<List<SessionState>> = _sessions
        .flatMapLatest { active ->
            if (active.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(active.map { it.session.state }) { it.toList() }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val canAddMore: Boolean
        get() = _sessions.value.size < SessionLimits.MAX_CONCURRENT_SESSIONS

    fun sessionFor(roomKey: String): WatchTogetherSession? =
        _sessions.value.firstOrNull { it.session.roomKey == roomKey }?.session

    fun itemFor(roomKey: String): LibraryItem? =
        _sessions.value.firstOrNull { it.session.roomKey == roomKey }?.item

    /** Adds a new session, or replaces the entry for the same roomKey in place (session recreated with a real player). */
    fun add(session: WatchTogetherSession, item: LibraryItem?) {
        val existing = _sessions.value
        if (existing.any { it.session.roomKey == session.roomKey }) {
            _sessions.value = existing.map {
                if (it.session.roomKey == session.roomKey) ActiveSession(session, item) else it
            }
            return
        }
        if (!canAddMore) return
        _sessions.value = existing + ActiveSession(session, item)
    }

    /** Ends and drops one session - guest "Leave", or host "Leave" (kills it for connected guests too). */
    fun remove(roomKey: String) {
        val target = _sessions.value.firstOrNull { it.session.roomKey == roomKey } ?: return
        target.session.end()
        _sessions.value = _sessions.value.filterNot { it.session.roomKey == roomKey }
    }

    /** Ends whichever session was added most recently - used by Room Screen's Cancel, where the
     * in-progress host session (created moments earlier by the same composable) isn't otherwise
     * addressable from the app-wide topBar closure it's cancelled from. */
    fun removeLast() {
        _sessions.value.lastOrNull()?.let { remove(it.session.roomKey) }
    }

    fun clearAll() {
        _sessions.value.forEach { it.session.end() }
        _sessions.value = emptyList()
    }
}
