package com.mofy.app.watchtogether.transport

/**
 * In-memory [SignalingChannel] for unit tests. Records outbound signals and
 * exposes [deliver] / [fail] helpers for the inbound path.
 */
class FakeSignalingChannel : SignalingChannel {

    data class Outbound(val toPeerId: String, val payload: String)

    private var listener: SignalingChannel.Listener? = null
    private var closed = false

    val sent = mutableListOf<Outbound>()

    override suspend fun sendSignal(toPeerId: String, payload: String) {
        checkOpen()
        sent += Outbound(toPeerId, payload)
    }

    override fun setListener(listener: SignalingChannel.Listener?) {
        this.listener = listener
    }

    override fun close() {
        closed = true
        listener = null
    }

    fun deliver(fromPeerId: String, payload: String) {
        checkOpen()
        listener?.onSignal(fromPeerId, payload)
            ?: error("no listener set")
    }

    fun fail(reason: String) {
        checkOpen()
        listener?.onSignalingFailed(reason)
    }

    private fun checkOpen() {
        check(!closed) { "signaling channel closed" }
    }
}
