package com.mofy.app.watchtogether.sync

import com.mofy.app.playback.PlayerController
import com.mofy.app.watchtogether.Participant
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.SessionLimits
import com.mofy.app.watchtogether.protocol.WtMessage
import com.mofy.app.watchtogether.protocol.WtMessageCodec
import com.mofy.app.watchtogether.sync.SyncEngineConfig.CONTROL_DEBOUNCE_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.CONTROL_IGNORE_WINDOW_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.DRIFT_THRESHOLD_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.DURATION_MISMATCH_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.HOST_DISCONNECT_GRACE_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.HOST_PEER_ID
import com.mofy.app.watchtogether.transport.WtTransport
import kotlinx.coroutines.runBlocking

/**
 * Pure sync rules for Watch Together (ADR 0006). Host fans out; guests peer
 * only with the host. No WebRTC/libVLC/Compose types.
 */
class SyncEngine(
    private val role: Role,
    private val roomKey: String,
    private val itemHash: String,
    localParticipant: Participant,
    private var player: PlayerController,
    private val transport: WtTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = {
        "p-${nextId++}"
    },
    private val events: Listener? = null,
) {
    fun interface Listener {
        fun onEvent(event: SyncEvent)
    }

    sealed interface SyncEvent {
        data class Error(val reason: String) : SyncEvent
        data class ParticipantsChanged(val participants: List<Participant>) : SyncEvent
        data object Joined : SyncEvent

        /** Guest-only: the host's connection dropped - not a clean [WatchTogetherSession.end],
         * so the caller should demote to a local solo player rather than just erroring out. */
        data object HostLost : SyncEvent
    }

    private var selfId: String = localParticipant.id
    private val selfDisplayName: String = localParticipant.displayName

    private val participants = linkedMapOf<String, Participant>()
    private val peerToParticipantId = mutableMapOf<String, String>()
    private val participantIdToPeer = mutableMapOf<String, String>()

    private var lastPositionTs: Long = Long.MIN_VALUE
    private var lastPositionMs: Long = Long.MIN_VALUE

    // Host-only: seq assigned to every Play/Pause/Seek this host broadcasts
    // (its own or a relayed guest control), so guests can order controls
    // against heartbeats even though guest wall clocks aren't comparable
    // (flows doc §4 - ts is host-assigned, not each phone's own clock).
    private var nextSeq: Long = 0L

    // Guest-only in practice: the ts of the last Play/Pause/Seek actually
    // applied. A Position heartbeat older than this must lose - otherwise
    // a heartbeat queued before a fresh control can arrive after it and
    // stomp it back to a stale state (flows doc §4.5).
    private var lastControlTs: Long = Long.MIN_VALUE

    // Seek debounce: rapid scrubs (many localSeek calls with no clock
    // advance between them) coalesce to one emitted Seek, "pending
    // replace, do not queue" - the newest wins, older ones are dropped
    // silently (flows doc §4.1/§4.3). Play/Pause are controls, not scrubs,
    // and are never debounced (flows doc §4 SE-CTL-09).
    private var pendingSeekMs: Long? = null
    private var pendingSeekDueAtMs: Long = Long.MAX_VALUE

    // Host-only: one pending relay seek per originating guest peer id, for
    // relaySeek()'s pending-replace behavior.
    private data class PendingRelaySeek(val msg: WtMessage.Seek, val dueAtMs: Long)
    private val pendingRelaySeeks = mutableMapOf<String, PendingRelaySeek>()

    // After applying a remote Play/Pause/Seek, local control calls within
    // this window are dropped so the local slider and the echo of its own
    // remote-applied position cannot ping-pong (flows doc §4.4).
    private var ignoreLocalControlsUntilMs: Long = Long.MIN_VALUE

    // Guest-only: when the host connection drops, this is set instead of
    // firing HostLost right away - a Wi-Fi blip and real host death look
    // identical at the instant of disconnect. HostLost only fires once the
    // grace period elapses without a reconnect (flows doc §5).
    private var hostDisconnectedAtMs: Long? = null
    private var hostLostFired: Boolean = false

    private var started = false
    private var closed = false

    // Last known playback state, kept in sync whenever `player` is actually
    // readable. When `player` is released (Back backgrounds the Player
    // screen; the session survives, only the local VLC instance dies), this
    // is what heartbeats/rebind/join-ack fall back to instead of lying with
    // 0/false (flows doc §3). Host extrapolates forward while playing
    // (virtual clock); guest never extrapolates - it only remembers the
    // last value applied from the host's fan-out (flows doc §3 "Guest
    // headless": guest does not own the clock).
    private var stateAnchorMs: Long = clock()
    private var statePositionMs: Long = 0L
    private var stateIsPlaying: Boolean = false

    init {
        participants[localParticipant.id] = localParticipant.copy(role = role)
    }

    fun participants(): List<Participant> = participants.values.toList()

    fun localParticipantId(): String = selfId

    fun start() {
        check(!closed) { "engine closed" }
        if (started) return
        started = true
        transport.setListener(object : WtTransport.Listener {
            override fun onMessage(fromPeerId: String, json: String) {
                handleMessage(fromPeerId, json)
            }

            override fun onPeerConnected(peerId: String) {
                if (role == Role.GUEST && peerId == HOST_PEER_ID) {
                    hostDisconnectedAtMs = null
                    hostLostFired = false
                }
            }

            override fun onPeerDisconnected(peerId: String) {
                handlePeerDisconnected(peerId)
            }

            override fun onTransportFailed(reason: String) {
                events?.onEvent(SyncEvent.Error(reason))
            }
        })
        if (role == Role.GUEST) {
            sendTo(HOST_PEER_ID, WtMessage.Join(roomKey, selfDisplayName, itemHash))
        }
        // Seed state from whatever the player already holds (tests, and
        // real callers, often set up play/pause/seek on the player before
        // start() and before the session ever goes headless).
        currentPositionMs()
    }

    /**
     * Test/pump seam for time-driven rules (debounce, ignore window, host
     * disconnect grace). Called on an interval by the production clock owner
     * once these rules are implemented. Currently a no-op - the flows-doc
     * spec tests exercise it and stay RED until the rules land.
     */
    fun tick() {
        flushPendingSeek()
        flushPendingRelaySeeks()
        checkHostDisconnectGrace()
    }

    private fun checkHostDisconnectGrace() {
        val disconnectedAt = hostDisconnectedAtMs ?: return
        if (hostLostFired) return
        if (clock() - disconnectedAt < HOST_DISCONNECT_GRACE_MS) return
        hostLostFired = true
        events?.onEvent(SyncEvent.HostLost)
    }

    private fun flushPendingSeek() {
        val pending = pendingSeekMs ?: return
        if (clock() < pendingSeekDueAtMs) return
        pendingSeekMs = null
        pendingSeekDueAtMs = Long.MAX_VALUE
        emitControl(WtMessage.Seek(pending, selfId))
    }

    fun close() {
        if (closed) return
        closed = true
        transport.setListener(null)
    }

    // Every access to `player` goes through these three - it can be
    // released out from under this engine at any time (Back backgrounds
    // the Player screen: only the local VlcPlayerController is torn down,
    // this engine/session stays alive per the redesign's session-survives-
    // navigation decision), and calling any method on an already-released
    // native player throws IllegalStateException. Confirmed on a real
    // device 3 separate times, at 3 separate unguarded call sites
    // (rebindPlayer, heartbeatTick, and this file's other direct
    // `player.xxx` reads/writes before this refactor) - centralizing here
    // instead of guarding each call site individually.
    private fun withPlayer(block: (PlayerController) -> Unit) {
        runCatching { block(player) }
    }

    /**
     * Reads the real player when possible (and syncs [statePositionMs] /
     * [stateIsPlaying] from it so the fallback below stays fresh). When the
     * player is released, falls back to last-known state - extrapolated
     * forward on the host's own clock while playing, frozen otherwise.
     * Guests never extrapolate: they don't own the timeline, only apply
     * what the host fans out (flows doc §3).
     */
    fun currentPositionMs(): Long {
        val pos = runCatching { player.positionMs }.getOrNull()
        val playing = runCatching { player.isPlaying }.getOrNull()
        if (pos != null && playing != null) {
            setState(pos, playing)
            return pos
        }
        return if (role == Role.HOST && stateIsPlaying) {
            statePositionMs + (clock() - stateAnchorMs)
        } else {
            statePositionMs
        }
    }

    fun currentIsPlaying(): Boolean {
        val pos = runCatching { player.positionMs }.getOrNull()
        val playing = runCatching { player.isPlaying }.getOrNull()
        if (pos != null && playing != null) {
            setState(pos, playing)
            return playing
        }
        return stateIsPlaying
    }

    private fun setState(positionMs: Long, isPlaying: Boolean) {
        statePositionMs = positionMs
        stateIsPlaying = isPlaying
        stateAnchorMs = clock()
    }

    fun localPlay() {
        if (clock() < ignoreLocalControlsUntilMs) return
        withPlayer { it.play() }
        val positionMs = currentPositionMs()
        setState(positionMs, true)
        emitControl(WtMessage.Play(positionMs, selfId))
    }

    fun localPause() {
        if (clock() < ignoreLocalControlsUntilMs) return
        val positionMs = currentPositionMs()
        withPlayer { it.pause() }
        setState(positionMs, false)
        emitControl(WtMessage.Pause(positionMs, selfId))
    }

    /**
     * Play/pause are controls, not scrubs - always emitted immediately
     * (flows doc §4 SE-CTL-09). Seek from a *bound* player is a UI scrub:
     * applied locally right away for responsiveness, but the network emit
     * is debounced ("pending replace, do not queue" - flows doc §4.1/§4.3)
     * so a rapid drag coalesces to the last position on [tick]. A seek
     * issued while the player is unbound/released (e.g. a notification's
     * seek control while headless) is a single deliberate action, not a
     * drag - it emits immediately.
     */
    fun localSeek(positionMs: Long) {
        if (clock() < ignoreLocalControlsUntilMs) return
        val bound = runCatching { player.positionMs }.isSuccess
        withPlayer { it.seekTo(positionMs) }
        setState(positionMs, currentIsPlaying())
        if (bound) {
            pendingSeekMs = positionMs
            pendingSeekDueAtMs = clock() + CONTROL_DEBOUNCE_MS
        } else {
            emitControl(WtMessage.Seek(positionMs, selfId))
        }
    }

    fun localSetSubtitle(index: Int?) {
        withPlayer { it.setSubtitleTrack(index) }
    }

    fun localSetAudio(index: Int?) {
        withPlayer { it.setAudioTrack(index) }
    }

    /**
     * Swaps the underlying player without tearing down signaling/transport -
     * used when the lobby's headless [PlayerController] (no media chosen
     * yet) gets replaced by a real one once playback actually starts.
     * Previously this meant ending the whole session and creating a fresh
     * one (new roomKey-scoped signaling server, new random port), which
     * silently dropped any guest already connected during the lobby phase -
     * confirmed on a real device: the guest's WebSocket closed (code 1001)
     * the instant "Start watching" was pressed, and the invite link/QR now
     * pointed at a dead port. Rebinding in place keeps the same signaling
     * server and connections alive across that transition.
     */
    fun rebindPlayer(newPlayer: PlayerController) {
        // The previous player can already be released by the time this
        // runs - confirmed crash on a real device: SoloPlayerScreen's
        // DisposableEffect is keyed on mediaUri, which starts as "" before
        // the DB-backed link flow resolves, then changes to the real URI
        // moments later - firing this twice, with the second call reading
        // positionMs off the first (already-disposed) player.
        val positionMs = currentPositionMs()
        val playing = currentIsPlaying()
        player = newPlayer
        newPlayer.seekTo(positionMs)
        if (playing) newPlayer.play() else newPlayer.pause()
        setState(positionMs, playing)
        // Host-only: notify already-connected guests so a real player
        // replacing the lobby's headless placeholder doesn't leave them
        // stuck on stale state. Only when transitioning to playing - a
        // paused rebind (e.g. guest re-entering Player) has nothing new to
        // announce and must not flip the room to playing.
        if (role == Role.HOST && playing) {
            emitControl(WtMessage.Play(positionMs, selfId))
        }
    }

    /** Host-only: emit a position heartbeat (tests call this instead of sleeping). */
    fun heartbeatTick() {
        if (role != Role.HOST || closed) return
        // Ticks every POSITION_HEARTBEAT_MS from WatchTogetherForegroundService's
        // background coroutine, independent of any screen - the local player
        // can be released (e.g. Back backgrounds the Player screen, only the
        // local VlcPlayerController is torn down, session stays alive) while
        // this loop keeps running. Confirmed crash on a real device:
        // IllegalStateException "can't get VLCObject instance" reading a
        // released player from this exact call, uncaught on a raw
        // coroutine - crashed the whole app, not just this loop.
        val positionMs = currentPositionMs()
        val isPlaying = currentIsPlaying()
        broadcast(
            WtMessage.Position(
                positionMs = positionMs,
                isPlaying = isPlaying,
                ts = clock(),
            ),
        )
    }

    private fun handleMessage(fromPeerId: String, json: String) {
        if (closed) return
        val msg = try {
            WtMessageCodec.decode(json)
        } catch (_: Exception) {
            return
        }
        when (msg) {
            is WtMessage.Join -> if (role == Role.HOST) handleJoin(fromPeerId, msg)
            is WtMessage.JoinAck -> if (role == Role.GUEST) handleJoinAck(msg)
            is WtMessage.ParticipantEvent -> handleParticipantEvent(msg)
            is WtMessage.Play -> handlePlay(fromPeerId, msg)
            is WtMessage.Pause -> handlePause(fromPeerId, msg)
            is WtMessage.Seek -> handleSeek(fromPeerId, msg)
            is WtMessage.Position -> handlePosition(msg)
            is WtMessage.Pref -> Unit
            is WtMessage.Error -> events?.onEvent(SyncEvent.Error(msg.reason))
            is WtMessage.Hello -> Unit
            is WtMessage.Bye -> events?.onEvent(SyncEvent.Error(msg.reason ?: "bye"))
        }
    }

    private fun handleJoin(fromPeerId: String, msg: WtMessage.Join) {
        if (msg.itemHash != itemHash) {
            sendTo(fromPeerId, WtMessage.Error("item_hash_mismatch"))
            return
        }
        if (participants.size >= SessionLimits.MAX_PARTICIPANTS) {
            sendTo(fromPeerId, WtMessage.Error("room_full"))
            return
        }
        val hostDurationMs = runCatching { player.durationMs }.getOrNull()
        if (msg.durationMs != null && hostDurationMs != null &&
            kotlin.math.abs(msg.durationMs - hostDurationMs) > DURATION_MISMATCH_MS
        ) {
            sendTo(fromPeerId, WtMessage.Error("duration_mismatch"))
            return
        }
        val id = idGenerator()
        val guest = Participant(id = id, displayName = msg.displayName, role = Role.GUEST)
        participants[id] = guest
        peerToParticipantId[fromPeerId] = id
        participantIdToPeer[id] = fromPeerId
        emitParticipants()

        sendTo(
            fromPeerId,
            WtMessage.JoinAck(
                participantId = id,
                participants = participants.values.toList(),
                positionMs = currentPositionMs(),
                isPlaying = currentIsPlaying(),
            ),
        )
        broadcast(
            WtMessage.ParticipantEvent(WtMessage.ParticipantEvent.Op.JOINED, guest),
            excludePeerId = fromPeerId,
        )
    }

    private fun handleJoinAck(msg: WtMessage.JoinAck) {
        val previous = participants.remove(selfId)
        selfId = msg.participantId
        participants.clear()
        msg.participants.forEach { participants[it.id] = it }
        if (participants[selfId] == null) {
            participants[selfId] = previous?.copy(id = selfId, role = Role.GUEST)
                ?: Participant(selfId, selfDisplayName, Role.GUEST)
        }
        applyPlayback(msg.positionMs, msg.isPlaying, forceSeek = true)
        hostDisconnectedAtMs = null
        hostLostFired = false
        emitParticipants()
        events?.onEvent(SyncEvent.Joined)
    }

    private fun handleParticipantEvent(msg: WtMessage.ParticipantEvent) {
        when (msg.op) {
            WtMessage.ParticipantEvent.Op.JOINED -> {
                participants[msg.participant.id] = msg.participant
                emitParticipants()
            }
            WtMessage.ParticipantEvent.Op.LEFT -> {
                val id = msg.participant.id
                participants.remove(id)
                participantIdToPeer.remove(id)?.let { peerToParticipantId.remove(it) }
                emitParticipants()
            }
        }
    }

    private fun emitParticipants() {
        events?.onEvent(SyncEvent.ParticipantsChanged(participants.values.toList()))
    }

    private fun handlePlay(fromPeerId: String, msg: WtMessage.Play) {
        if (msg.by == selfId) return
        lastControlTs = maxOf(lastControlTs, msg.ts ?: clock())
        ignoreLocalControlsUntilMs = clock() + CONTROL_IGNORE_WINDOW_MS
        applyPlayback(msg.positionMs, playing = true, forceSeek = true)
        if (role == Role.HOST) {
            broadcast(msg, excludePeerId = fromPeerId)
        }
    }

    private fun handlePause(fromPeerId: String, msg: WtMessage.Pause) {
        if (msg.by == selfId) return
        lastControlTs = maxOf(lastControlTs, msg.ts ?: clock())
        ignoreLocalControlsUntilMs = clock() + CONTROL_IGNORE_WINDOW_MS
        applyPlayback(msg.positionMs, playing = false, forceSeek = true)
        if (role == Role.HOST) {
            broadcast(msg, excludePeerId = fromPeerId)
        }
    }

    private fun handleSeek(fromPeerId: String, msg: WtMessage.Seek) {
        if (msg.by == selfId) return
        lastControlTs = maxOf(lastControlTs, msg.ts ?: clock())
        ignoreLocalControlsUntilMs = clock() + CONTROL_IGNORE_WINDOW_MS
        withPlayer { it.seekTo(msg.positionMs) }
        setState(msg.positionMs, currentIsPlaying())
        if (role == Role.HOST) {
            relaySeek(fromPeerId, msg)
        }
    }

    /**
     * Host-only relay of a guest's seek. Deferred rather than broadcast
     * immediately: if another seek from the *same* guest arrives before
     * this one is flushed, the new one supersedes and sends now - "pending
     * replace, do not queue" (flows doc §4.3), so a guest scrubbing their
     * own slider doesn't flood everyone else with every intermediate
     * position. A lone seek (no immediate follow-up) flushes on [tick]
     * once the debounce window elapses.
     */
    private fun relaySeek(fromPeerId: String, msg: WtMessage.Seek) {
        if (pendingRelaySeeks.remove(fromPeerId) != null) {
            broadcast(msg, excludePeerId = fromPeerId)
        } else {
            pendingRelaySeeks[fromPeerId] = PendingRelaySeek(msg, clock() + CONTROL_DEBOUNCE_MS)
        }
    }

    private fun flushPendingRelaySeeks() {
        if (pendingRelaySeeks.isEmpty()) return
        val due = pendingRelaySeeks.filterValues { clock() >= it.dueAtMs }
        due.keys.forEach { peerId ->
            val pending = pendingRelaySeeks.remove(peerId) ?: return@forEach
            broadcast(pending.msg, excludePeerId = peerId)
        }
    }

    private fun handlePosition(msg: WtMessage.Position) {
        if (role != Role.GUEST) return
        if (msg.ts < lastControlTs) return
        if (!isNewerPosition(msg)) return
        lastPositionTs = msg.ts
        lastPositionMs = msg.positionMs
        val drift = kotlin.math.abs(msg.positionMs - currentPositionMs())
        val forceSeek = drift > DRIFT_THRESHOLD_MS
        applyPlayback(msg.positionMs, msg.isPlaying, forceSeek = forceSeek)
    }

    private fun isNewerPosition(msg: WtMessage.Position): Boolean {
        if (msg.ts > lastPositionTs) return true
        if (msg.ts < lastPositionTs) return false
        return msg.positionMs > lastPositionMs
    }

    private fun handlePeerDisconnected(peerId: String) {
        if (role == Role.GUEST && peerId == HOST_PEER_ID) {
            // A Wi-Fi blip and real host death look identical at the
            // instant of disconnect - start the grace timer instead of
            // firing HostLost immediately; tick() fires it once the grace
            // period elapses without a reconnect (flows doc §5).
            if (hostDisconnectedAtMs == null) {
                hostDisconnectedAtMs = clock()
            }
            return
        }
        val participantId = peerToParticipantId.remove(peerId) ?: return
        participantIdToPeer.remove(participantId)
        val removed = participants.remove(participantId) ?: return
        emitParticipants()
        if (role == Role.HOST) {
            broadcast(
                WtMessage.ParticipantEvent(WtMessage.ParticipantEvent.Op.LEFT, removed),
            )
        }
    }

    private fun applyPlayback(positionMs: Long, playing: Boolean, forceSeek: Boolean) {
        withPlayer {
            if (forceSeek) it.seekTo(positionMs)
            if (playing) it.play() else it.pause()
        }
        setState(positionMs, playing)
    }

    private fun emitControl(msg: WtMessage) {
        when (role) {
            Role.HOST -> broadcast(msg)
            Role.GUEST -> sendTo(HOST_PEER_ID, msg)
        }
    }

    private fun broadcast(msg: WtMessage, excludePeerId: String? = null) {
        val json = WtMessageCodec.encode(stampIfHost(msg))
        runBlocking {
            transport.sendToAll(json, excludePeerId)
        }
    }

    /**
     * Host assigns ts/seq on every Play/Pause/Seek it broadcasts - its own
     * local control or a guest's relayed one - so all guests compare
     * against the same clock (flows doc §4: "ts is host-assigned, not each
     * phone's own clock"). No-op for guests and for other message types.
     */
    private fun stampIfHost(msg: WtMessage): WtMessage {
        if (role != Role.HOST) return msg
        val ts = clock()
        val seq = nextSeq++
        return when (msg) {
            is WtMessage.Play -> msg.copy(ts = ts, seq = seq)
            is WtMessage.Pause -> msg.copy(ts = ts, seq = seq)
            is WtMessage.Seek -> msg.copy(ts = ts, seq = seq)
            else -> msg
        }
    }

    private fun sendTo(peerId: String, msg: WtMessage) {
        val json = WtMessageCodec.encode(msg)
        runBlocking {
            transport.send(peerId, json)
        }
    }

    private companion object {
        private var nextId = 1
    }
}
