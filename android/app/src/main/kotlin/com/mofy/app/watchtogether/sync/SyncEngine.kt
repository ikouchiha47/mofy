package com.mofy.app.watchtogether.sync

import com.mofy.app.playback.PlayerController
import com.mofy.app.watchtogether.Participant
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.SessionLimits
import com.mofy.app.watchtogether.protocol.WtMessage
import com.mofy.app.watchtogether.protocol.WtMessageCodec
import com.mofy.app.watchtogether.sync.SyncEngineConfig.DRIFT_THRESHOLD_MS
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
    private var started = false
    private var closed = false

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

            override fun onPeerConnected(peerId: String) = Unit

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
    }

    /**
     * Test/pump seam for time-driven rules (debounce, ignore window, host
     * disconnect grace). Called on an interval by the production clock owner
     * once these rules are implemented. Currently a no-op - the flows-doc
     * spec tests exercise it and stay RED until the rules land.
     */
    fun tick() {
        // No-op stub. Debounce flush, ignore-window expiry, and grace
        // expiry belong here once implemented.
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
    private fun safePositionMs(): Long = runCatching { player.positionMs }.getOrDefault(0L)
    private fun safeIsPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)
    private fun withPlayer(block: (PlayerController) -> Unit) {
        runCatching { block(player) }
    }

    fun localPlay() {
        withPlayer { it.play() }
        emitControl(WtMessage.Play(safePositionMs(), selfId))
    }

    fun localPause() {
        withPlayer { it.pause() }
        emitControl(WtMessage.Pause(safePositionMs(), selfId))
    }

    fun localSeek(positionMs: Long) {
        withPlayer { it.seekTo(positionMs) }
        emitControl(WtMessage.Seek(positionMs, selfId))
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
        val positionMs = safePositionMs()
        player = newPlayer
        newPlayer.seekTo(positionMs)
        localPlay() // also broadcasts Play so any already-connected guest starts too
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
        val positionMs = safePositionMs()
        val isPlaying = safeIsPlaying()
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
                positionMs = safePositionMs(),
                isPlaying = safeIsPlaying(),
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
        applyPlayback(msg.positionMs, playing = true, forceSeek = true)
        if (role == Role.HOST) {
            broadcast(msg, excludePeerId = fromPeerId)
        }
    }

    private fun handlePause(fromPeerId: String, msg: WtMessage.Pause) {
        if (msg.by == selfId) return
        applyPlayback(msg.positionMs, playing = false, forceSeek = true)
        if (role == Role.HOST) {
            broadcast(msg, excludePeerId = fromPeerId)
        }
    }

    private fun handleSeek(fromPeerId: String, msg: WtMessage.Seek) {
        if (msg.by == selfId) return
        withPlayer { it.seekTo(msg.positionMs) }
        if (role == Role.HOST) {
            broadcast(msg, excludePeerId = fromPeerId)
        }
    }

    private fun handlePosition(msg: WtMessage.Position) {
        if (role != Role.GUEST) return
        if (!isNewerPosition(msg)) return
        lastPositionTs = msg.ts
        lastPositionMs = msg.positionMs
        val drift = kotlin.math.abs(msg.positionMs - safePositionMs())
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
            // Guests never populate peerToParticipantId (only handleJoin,
            // host-only, does) - without this branch, a guest losing its
            // one connection to the host silently emitted nothing at all,
            // and local playback just kept running forever, unsynced, with
            // no signal to the caller that the room is actually gone.
            events?.onEvent(SyncEvent.HostLost)
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
    }

    private fun emitControl(msg: WtMessage) {
        when (role) {
            Role.HOST -> broadcast(msg)
            Role.GUEST -> sendTo(HOST_PEER_ID, msg)
        }
    }

    private fun broadcast(msg: WtMessage, excludePeerId: String? = null) {
        val json = WtMessageCodec.encode(msg)
        runBlocking {
            transport.sendToAll(json, excludePeerId)
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
