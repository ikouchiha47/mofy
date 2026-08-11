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
    private val player: PlayerController,
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

    fun close() {
        if (closed) return
        closed = true
        transport.setListener(null)
    }

    fun localPlay() {
        player.play()
        emitControl(WtMessage.Play(player.positionMs, selfId))
    }

    fun localPause() {
        player.pause()
        emitControl(WtMessage.Pause(player.positionMs, selfId))
    }

    fun localSeek(positionMs: Long) {
        player.seekTo(positionMs)
        emitControl(WtMessage.Seek(positionMs, selfId))
    }

    fun localSetSubtitle(index: Int?) {
        player.setSubtitleTrack(index)
    }

    fun localSetAudio(index: Int?) {
        player.setAudioTrack(index)
    }

    /** Host-only: emit a position heartbeat (tests call this instead of sleeping). */
    fun heartbeatTick() {
        if (role != Role.HOST || closed) return
        broadcast(
            WtMessage.Position(
                positionMs = player.positionMs,
                isPlaying = player.isPlaying,
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
                positionMs = player.positionMs,
                isPlaying = player.isPlaying,
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
        player.seekTo(msg.positionMs)
        if (role == Role.HOST) {
            broadcast(msg, excludePeerId = fromPeerId)
        }
    }

    private fun handlePosition(msg: WtMessage.Position) {
        if (role != Role.GUEST) return
        if (!isNewerPosition(msg)) return
        lastPositionTs = msg.ts
        lastPositionMs = msg.positionMs
        val drift = kotlin.math.abs(msg.positionMs - player.positionMs)
        val forceSeek = drift > DRIFT_THRESHOLD_MS
        applyPlayback(msg.positionMs, msg.isPlaying, forceSeek = forceSeek)
    }

    private fun isNewerPosition(msg: WtMessage.Position): Boolean {
        if (msg.ts > lastPositionTs) return true
        if (msg.ts < lastPositionTs) return false
        return msg.positionMs > lastPositionMs
    }

    private fun handlePeerDisconnected(peerId: String) {
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
        if (forceSeek) {
            player.seekTo(positionMs)
        }
        if (playing) player.play() else player.pause()
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
