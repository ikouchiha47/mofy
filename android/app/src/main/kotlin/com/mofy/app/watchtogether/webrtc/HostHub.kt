package com.mofy.app.watchtogether.webrtc

import com.mofy.app.watchtogether.SessionLimits
import com.mofy.app.watchtogether.transport.SignalingChannel
import com.mofy.app.watchtogether.transport.WtTransport
import kotlinx.coroutines.runBlocking
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-side star hub: one PeerConnection + DataChannel per guest.
 * No media tracks/transceivers (ADR 0006).
 */
class HostHub(
    private val signaling: SignalingChannel,
    private val factory: PeerConnectionFactory = PeerConnectionFactoryHolder.get(),
) : WtTransport {

    private val listenerRef = AtomicReference<WtTransport.Listener?>(null)
    private val peers = ConcurrentHashMap<String, PeerSlot>()
    private val pendingIce = ConcurrentHashMap<String, CopyOnWriteArrayList<IceCandidate>>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wt-host-hub").apply { isDaemon = true }
    }
    private var closed = false

    fun start() {
        signaling.setListener(object : SignalingChannel.Listener {
            override fun onSignal(fromPeerId: String, payload: String) {
                executor.execute { handleSignal(fromPeerId, payload) }
            }

            override fun onSignalingFailed(reason: String) {
                listenerRef.get()?.onTransportFailed(reason)
            }
        })
    }

    override suspend fun send(peerId: String, json: String) {
        val slot = peers[peerId] ?: return
        slot.send(json)
    }

    override suspend fun sendToAll(json: String, excludePeerId: String?) {
        peers.forEach { (id, slot) ->
            if (id != excludePeerId) slot.send(json)
        }
    }

    override fun setListener(listener: WtTransport.Listener?) {
        listenerRef.set(listener)
    }

    override fun close() {
        closed = true
        signaling.setListener(null)
        peers.keys.toList().forEach { removePeer(it, notify = false) }
        executor.shutdownNow()
    }

    private fun handleSignal(fromPeerId: String, payload: String) {
        if (closed) return
        val signal = try {
            RtcSignalCodec.decode(payload)
        } catch (_: Exception) {
            return
        }
        when (signal) {
            is RtcSignal.JoinRtc -> onGuestJoinRtc(fromPeerId)
            is RtcSignal.Answer -> onAnswer(fromPeerId, signal)
            is RtcSignal.Ice -> onRemoteIce(fromPeerId, signal)
            is RtcSignal.Offer -> Unit
        }
    }

    private fun onGuestJoinRtc(peerId: String) {
        if (peers.containsKey(peerId)) return
        if (peers.size >= SessionLimits.MAX_PARTICIPANTS - 1) {
            listenerRef.get()?.onTransportFailed("room_full")
            return
        }
        val pc = factory.createPeerConnection(rtcConfig(), pcObserver(peerId))
            ?: run {
                listenerRef.get()?.onTransportFailed("pc_create_failed")
                return
            }
        val init = DataChannel.Init().apply { ordered = true }
        val dc = pc.createDataChannel(WtWebRtcConfig.CONTROL_CHANNEL_LABEL, init)
            ?: run {
                pc.dispose()
                listenerRef.get()?.onTransportFailed("dc_create_failed")
                return
            }
        val slot = PeerSlot(peerId, pc, dc)
        wireDataChannel(slot)
        peers[peerId] = slot

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        sendRtc(peerId, RtcSignal.Offer(desc.description))
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    private fun onAnswer(peerId: String, answer: RtcSignal.Answer) {
        val slot = peers[peerId] ?: return
        val desc = SessionDescription(SessionDescription.Type.ANSWER, answer.sdp)
        slot.pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pendingIce.remove(peerId)?.forEach { slot.pc.addIceCandidate(it) }
            }
        }, desc)
    }

    private fun onRemoteIce(peerId: String, ice: RtcSignal.Ice) {
        val candidate = IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        val slot = peers[peerId]
        if (slot?.pc?.remoteDescription != null) {
            slot.pc.addIceCandidate(candidate)
        } else {
            pendingIce.getOrPut(peerId) { CopyOnWriteArrayList() }.add(candidate)
        }
    }

    private fun wireDataChannel(slot: PeerSlot) {
        slot.dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                when (slot.dc.state()) {
                    DataChannel.State.OPEN -> listenerRef.get()?.onPeerConnected(slot.peerId)
                    DataChannel.State.CLOSED, DataChannel.State.CLOSING -> {
                        removePeer(slot.peerId, notify = true)
                    }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, StandardCharsets.UTF_8)
                listenerRef.get()?.onMessage(slot.peerId, text)
            }
        })
    }

    private fun pcObserver(peerId: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.FAILED ->
                    listenerRef.get()?.onTransportFailed("ice_failed:$peerId")
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.CLOSED,
                -> removePeer(peerId, notify = true)
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            sendRtc(
                peerId,
                RtcSignal.Ice(
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                ),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
        override fun onDataChannel(dc: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(
            receiver: org.webrtc.RtpReceiver?,
            mediaStreams: Array<out org.webrtc.MediaStream>?,
        ) = Unit
    }

    private fun removePeer(peerId: String, notify: Boolean) {
        val slot = peers.remove(peerId) ?: return
        pendingIce.remove(peerId)
        try {
            slot.dc.unregisterObserver()
            slot.dc.close()
        } catch (_: Exception) {
        }
        try {
            slot.pc.close()
            slot.pc.dispose()
        } catch (_: Exception) {
        }
        if (notify && !closed) {
            listenerRef.get()?.onPeerDisconnected(peerId)
        }
    }

    private fun sendRtc(toPeerId: String, signal: RtcSignal) {
        runBlocking {
            signaling.sendSignal(toPeerId, RtcSignalCodec.encode(signal))
        }
    }

    private data class PeerSlot(
        val peerId: String,
        val pc: PeerConnection,
        val dc: DataChannel,
    ) {
        fun send(json: String) {
            if (dc.state() != DataChannel.State.OPEN) return
            val buf = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
            dc.send(DataChannel.Buffer(buf, false))
        }
    }

    companion object {
        fun rtcConfig(): PeerConnection.RTCConfiguration {
            val servers = WtWebRtcConfig.STUN_URLS.map { url ->
                PeerConnection.IceServer.builder(url).createIceServer()
            }
            return PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
        }
    }
}

internal open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
