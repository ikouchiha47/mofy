package com.mofy.app.watchtogether.webrtc

import com.mofy.app.watchtogether.sync.SyncEngineConfig.HOST_PEER_ID
import com.mofy.app.watchtogether.transport.SignalingChannel
import com.mofy.app.watchtogether.transport.WtTransport
import kotlinx.coroutines.runBlocking
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Guest-side single peer connection to the host hub. Answerer role.
 * No media tracks/transceivers (ADR 0006).
 */
class GuestPeer(
    private val signaling: SignalingChannel,
    private val factory: PeerConnectionFactory = PeerConnectionFactoryHolder.get(),
) : WtTransport {

    private val listenerRef = AtomicReference<WtTransport.Listener?>(null)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wt-guest-peer").apply { isDaemon = true }
    }
    private val pendingOutgoing = CopyOnWriteArrayList<String>()
    private val pendingIce = CopyOnWriteArrayList<IceCandidate>()

    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var closed = false
    private var hostConnected = false

    fun start() {
        signaling.setListener(object : SignalingChannel.Listener {
            override fun onSignal(fromPeerId: String, payload: String) {
                executor.execute { handleSignal(fromPeerId, payload) }
            }

            override fun onSignalingFailed(reason: String) {
                listenerRef.get()?.onTransportFailed(reason)
            }
        })
        runBlocking {
            signaling.sendSignal(HOST_PEER_ID, RtcSignalCodec.encode(RtcSignal.JoinRtc))
        }
    }

    override suspend fun send(peerId: String, json: String) {
        if (peerId != HOST_PEER_ID) return
        val channel = dc
        if (channel != null && channel.state() == DataChannel.State.OPEN) {
            val buf = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
            channel.send(DataChannel.Buffer(buf, false))
        } else {
            pendingOutgoing.add(json)
        }
    }

    override suspend fun sendToAll(json: String, excludePeerId: String?) {
        if (excludePeerId == HOST_PEER_ID) return
        send(HOST_PEER_ID, json)
    }

    override fun setListener(listener: WtTransport.Listener?) {
        listenerRef.set(listener)
    }

    override fun close() {
        closed = true
        signaling.setListener(null)
        teardownPc(notify = false)
        executor.shutdownNow()
    }

    private fun handleSignal(fromPeerId: String, payload: String) {
        if (closed || fromPeerId != HOST_PEER_ID && fromPeerId != "host") return
        val signal = try {
            RtcSignalCodec.decode(payload)
        } catch (_: Exception) {
            return
        }
        when (signal) {
            is RtcSignal.Offer -> onOffer(signal)
            is RtcSignal.Ice -> onRemoteIce(signal)
            is RtcSignal.Answer, RtcSignal.JoinRtc -> Unit
        }
    }

    private fun onOffer(offer: RtcSignal.Offer) {
        if (pc != null) return
        val connection = factory.createPeerConnection(HostHub.rtcConfig(), pcObserver())
            ?: run {
                listenerRef.get()?.onTransportFailed("pc_create_failed")
                return
            }
        pc = connection
        val desc = SessionDescription(SessionDescription.Type.OFFER, offer.sdp)
        connection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pendingIce.forEach { connection.addIceCandidate(it) }
                pendingIce.clear()
                connection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        connection.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                sendRtc(RtcSignal.Answer(answer.description))
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, desc)
    }

    private fun onRemoteIce(ice: RtcSignal.Ice) {
        val candidate = IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        val connection = pc
        if (connection?.remoteDescription != null) {
            connection.addIceCandidate(candidate)
        } else {
            pendingIce.add(candidate)
        }
    }

    private fun wireDataChannel(channel: DataChannel) {
        dc = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> {
                        if (!hostConnected) {
                            hostConnected = true
                            listenerRef.get()?.onPeerConnected(HOST_PEER_ID)
                        }
                        flushPending()
                    }
                    DataChannel.State.CLOSED, DataChannel.State.CLOSING -> {
                        teardownPc(notify = true)
                    }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, StandardCharsets.UTF_8)
                listenerRef.get()?.onMessage(HOST_PEER_ID, text)
            }
        })
    }

    private fun flushPending() {
        val channel = dc ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val batch = pendingOutgoing.toList()
        pendingOutgoing.clear()
        batch.forEach { json ->
            val buf = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
            channel.send(DataChannel.Buffer(buf, false))
        }
    }

    private fun pcObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.FAILED ->
                    listenerRef.get()?.onTransportFailed("ice_failed")
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.CLOSED,
                -> teardownPc(notify = true)
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            sendRtc(
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
        override fun onDataChannel(channel: DataChannel) {
            wireDataChannel(channel)
        }

        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(
            receiver: org.webrtc.RtpReceiver?,
            mediaStreams: Array<out org.webrtc.MediaStream>?,
        ) = Unit
    }

    private fun teardownPc(notify: Boolean) {
        val wasConnected = hostConnected
        hostConnected = false
        try {
            dc?.unregisterObserver()
            dc?.close()
        } catch (_: Exception) {
        }
        dc = null
        try {
            pc?.close()
            pc?.dispose()
        } catch (_: Exception) {
        }
        pc = null
        if (notify && wasConnected && !closed) {
            listenerRef.get()?.onPeerDisconnected(HOST_PEER_ID)
        }
    }

    private fun sendRtc(signal: RtcSignal) {
        runBlocking {
            signaling.sendSignal(HOST_PEER_ID, RtcSignalCodec.encode(signal))
        }
    }
}
