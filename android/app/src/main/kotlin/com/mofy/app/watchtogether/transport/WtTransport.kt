package com.mofy.app.watchtogether.transport

/**
 * Data-plane port for Watch Together control messages (ADR 0006).
 * Implementations carry opaque JSON text; no WebRTC types leak here.
 */
interface WtTransport {
    /** Send one encoded JSON text frame to one peer (or hub). */
    suspend fun send(peerId: String, json: String)

    /** Broadcast to all connected peers except optional excludeId. */
    suspend fun sendToAll(json: String, excludePeerId: String? = null)

    fun setListener(listener: Listener?)

    fun close()

    interface Listener {
        fun onMessage(fromPeerId: String, json: String)
        fun onPeerConnected(peerId: String)
        fun onPeerDisconnected(peerId: String)
        fun onTransportFailed(reason: String)
    }
}
