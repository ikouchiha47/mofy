package com.mofy.app.watchtogether

import android.content.Context
import com.mofy.app.playback.PlayerController
import com.mofy.app.watchtogether.signaling.EmbeddedSignalingServer
import com.mofy.app.watchtogether.signaling.OkHttpSignalingChannel
import com.mofy.app.watchtogether.signaling.SignalingSettings
import com.mofy.app.watchtogether.sync.SyncEngine
import com.mofy.app.watchtogether.sync.SyncEngineConfig
import com.mofy.app.watchtogether.transport.SignalingChannel
import com.mofy.app.watchtogether.transport.WtTransport
import com.mofy.app.watchtogether.webrtc.GuestPeer
import com.mofy.app.watchtogether.webrtc.HostHub
import com.mofy.app.watchtogether.webrtc.PeerConnectionFactoryHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Facade over SyncEngine + signaling + WebRTC transport. No Compose.
 * Does **not** own [PlayerController] — caller releases the player.
 */
class WatchTogetherSession private constructor(
    val roomKey: String,
    val role: Role,
    val itemHash: String,
    val signalingUrl: String?,
    private val engine: SyncEngine,
    private val transport: WtTransport,
    private val signaling: SignalingChannel?,
    private val embeddedServer: EmbeddedSignalingServer?,
    private val player: PlayerController,
    private val localParticipant: Participant,
) {
    sealed interface WtEvent {
        data class ParticipantJoined(val participant: Participant) : WtEvent
        data class ParticipantLeft(val participantId: String) : WtEvent
        data class Error(val reason: String) : WtEvent
        data object Joined : WtEvent
        data object Ended : WtEvent
    }

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WtEvent>(
        replay = 8,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<WtEvent> = _events.asSharedFlow()

    val deepLink: String
        get() = RoomCode.toDeepLink(roomKey, signalingUrl)

    fun localPlay() = engine.localPlay().also { refreshState() }
    fun localPause() = engine.localPause().also { refreshState() }
    fun localSeek(positionMs: Long) = engine.localSeek(positionMs).also { refreshState() }
    fun localSetSubtitle(index: Int?) = engine.localSetSubtitle(index)
    fun localSetAudio(index: Int?) = engine.localSetAudio(index)
    fun heartbeatTick() = engine.heartbeatTick()

    fun end() {
        engine.close()
        transport.close()
        signaling?.close()
        embeddedServer?.stop()
        _events.tryEmit(WtEvent.Ended)
    }

    private fun onSyncEvent(event: SyncEngine.SyncEvent) {
        when (event) {
            is SyncEngine.SyncEvent.Error -> _events.tryEmit(WtEvent.Error(event.reason))
            is SyncEngine.SyncEvent.Joined -> _events.tryEmit(WtEvent.Joined)
            is SyncEngine.SyncEvent.ParticipantsChanged -> {
                val previous = _state.value.participants.map { it.id }.toSet()
                event.participants.forEach { p ->
                    if (p.id !in previous && p.id != engine.localParticipantId()) {
                        _events.tryEmit(WtEvent.ParticipantJoined(p))
                    }
                }
                previous.forEach { id ->
                    if (event.participants.none { it.id == id }) {
                        _events.tryEmit(WtEvent.ParticipantLeft(id))
                    }
                }
                refreshState()
            }
        }
    }

    private fun refreshState() {
        _state.value = snapshot()
    }

    private fun snapshot(): SessionState = SessionState(
        roomKey = roomKey,
        itemHash = itemHash,
        role = role,
        localParticipantId = engine.localParticipantId(),
        participants = engine.participants(),
        positionMs = player.positionMs,
        isPlaying = player.isPlaying,
    )

    companion object {
        fun host(
            itemHash: String,
            displayName: String,
            player: PlayerController,
            appContext: Context,
            roomKey: String = RoomKey.generate(),
        ): WatchTogetherSession {
            PeerConnectionFactoryHolder.init(appContext)
            val hostParticipant = Participant(
                id = "host-${UUID.randomUUID().toString().take(8)}",
                displayName = displayName,
                role = Role.HOST,
            )

            val relayBase = SignalingSettings.relayBaseUrl
            val embedded: EmbeddedSignalingServer?
            val signalingUrl: String
            if (relayBase != null) {
                embedded = null
                signalingUrl = SignalingSettings.urlForRoom(roomKey, relayBase)
                    ?: error("bad relay base")
            } else {
                embedded = EmbeddedSignalingServer().also { it.start() }
                signalingUrl = embedded.localUrl(roomKey)
            }

            // Signaling peer id must be "host" so guests can address JoinRtc/ICE.
            val signaling = OkHttpSignalingChannel(
                url = signalingUrl,
                peerId = SyncEngineConfig.HOST_PEER_ID,
                roomKey = roomKey,
            ).also { it.connect() }

            val hub = HostHub(signaling = signaling).also { it.start() }
            return create(
                roomKey = roomKey,
                role = Role.HOST,
                itemHash = itemHash,
                signalingUrl = signalingUrl,
                localParticipant = hostParticipant,
                player = player,
                transport = hub,
                signaling = signaling,
                embeddedServer = embedded,
            )
        }

        fun guest(
            roomKey: String,
            signalingUrl: String,
            itemHash: String,
            displayName: String,
            player: PlayerController,
            appContext: Context,
        ): WatchTogetherSession {
            PeerConnectionFactoryHolder.init(appContext)
            val guestParticipant = Participant(
                id = "guest-${UUID.randomUUID().toString().take(8)}",
                displayName = displayName,
                role = Role.GUEST,
            )
            val signaling = OkHttpSignalingChannel(
                url = signalingUrl,
                peerId = guestParticipant.id,
                roomKey = roomKey,
            ).also { it.connect() }
            val peer = GuestPeer(signaling = signaling)
            val session = create(
                roomKey = roomKey,
                role = Role.GUEST,
                itemHash = itemHash,
                signalingUrl = signalingUrl,
                localParticipant = guestParticipant,
                player = player,
                transport = peer,
                signaling = signaling,
                embeddedServer = null,
            )
            peer.start()
            return session
        }

        /**
         * Unit-test entry: fakes only, no WebRTC/Android.
         * Player is not owned; caller must not rely on transport being WebRTC.
         */
        fun forTest(
            roomKey: String,
            role: Role,
            itemHash: String,
            localParticipant: Participant,
            player: PlayerController,
            transport: WtTransport,
            signalingUrl: String? = null,
        ): WatchTogetherSession = create(
            roomKey = roomKey,
            role = role,
            itemHash = itemHash,
            signalingUrl = signalingUrl,
            localParticipant = localParticipant,
            player = player,
            transport = transport,
            signaling = null,
            embeddedServer = null,
        )

        private fun create(
            roomKey: String,
            role: Role,
            itemHash: String,
            signalingUrl: String?,
            localParticipant: Participant,
            player: PlayerController,
            transport: WtTransport,
            signaling: SignalingChannel?,
            embeddedServer: EmbeddedSignalingServer?,
        ): WatchTogetherSession {
            lateinit var session: WatchTogetherSession
            val engine = SyncEngine(
                role = role,
                roomKey = roomKey,
                itemHash = itemHash,
                localParticipant = localParticipant,
                player = player,
                transport = transport,
                events = SyncEngine.Listener { event -> session.onSyncEvent(event) },
            )
            session = WatchTogetherSession(
                roomKey = roomKey,
                role = role,
                itemHash = itemHash,
                signalingUrl = signalingUrl,
                engine = engine,
                transport = transport,
                signaling = signaling,
                embeddedServer = embeddedServer,
                player = player,
                localParticipant = localParticipant,
            )
            engine.start()
            session.refreshState()
            return session
        }
    }
}
