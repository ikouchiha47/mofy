package com.mofy.app.watchtogether.transport

/**
 * In-memory [WtTransport] for unit tests. Records outbound frames and exposes
 * [deliver] / [connectPeer] / [disconnectPeer] / [fail] helpers so tests can
 * drive the inbound path without WebRTC.
 */
class FakeWtTransport : WtTransport {

    data class Outbound(val peerId: String, val json: String)

    private val connected = linkedSetOf<String>()
    private var listener: WtTransport.Listener? = null
    private var closed = false

    val sent = mutableListOf<Outbound>()
    val sentToAll = mutableListOf<Pair<String, String?>>()

    override suspend fun send(peerId: String, json: String) {
        checkOpen()
        sent += Outbound(peerId, json)
    }

    override suspend fun sendToAll(json: String, excludePeerId: String?) {
        checkOpen()
        sentToAll += json to excludePeerId
        connected
            .filter { it != excludePeerId }
            .forEach { peerId -> sent += Outbound(peerId, json) }
    }

    override fun setListener(listener: WtTransport.Listener?) {
        this.listener = listener
    }

    override fun close() {
        closed = true
        listener = null
        connected.clear()
    }

    fun connectPeer(peerId: String) {
        checkOpen()
        if (connected.add(peerId)) {
            listener?.onPeerConnected(peerId)
        }
    }

    fun disconnectPeer(peerId: String) {
        checkOpen()
        if (connected.remove(peerId)) {
            listener?.onPeerDisconnected(peerId)
        }
    }

    fun deliver(fromPeerId: String, json: String) {
        checkOpen()
        listener?.onMessage(fromPeerId, json)
            ?: error("no listener set")
    }

    fun fail(reason: String) {
        checkOpen()
        listener?.onTransportFailed(reason)
    }

    fun connectedPeers(): Set<String> = connected.toSet()

    private fun checkOpen() {
        check(!closed) { "transport closed" }
    }
}
