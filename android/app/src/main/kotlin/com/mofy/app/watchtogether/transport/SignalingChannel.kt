package com.mofy.app.watchtogether.transport

/**
 * Bootstrap-only port for SDP/ICE exchange (JSEP). Payloads are opaque strings;
 * once the DataChannel is up, session traffic uses [WtTransport] instead.
 */
interface SignalingChannel {
    suspend fun sendSignal(toPeerId: String, payload: String)

    fun setListener(listener: Listener?)

    fun close()

    interface Listener {
        fun onSignal(fromPeerId: String, payload: String)
        fun onSignalingFailed(reason: String)
    }
}
