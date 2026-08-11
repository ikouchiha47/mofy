package com.mofy.app.watchtogether.signaling

import com.mofy.app.watchtogether.transport.SignalingChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [SignalingChannel] over OkHttp WebSocket. Used by host (against embedded or
 * remote relay) and guests (against `sig=` / [SignalingSettings.relayBaseUrl]).
 *
 * Wire protocol only — never carries play/pause (DataChannel after ICE).
 */
class OkHttpSignalingChannel(
    private val url: String,
    private val peerId: String,
    private val roomKey: String,
    private val client: OkHttpClient = defaultClient,
) : SignalingChannel {

    private val listenerRef = AtomicReference<SignalingChannel.Listener?>(null)
    private val socketRef = AtomicReference<WebSocket?>(null)
    private val openLatch = CountDownLatch(1)
    private val helloSent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun connect(timeoutMs: Long = 5_000) {
        check(!closed.get()) { "closed" }
        check(socketRef.get() == null) { "already connected" }
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = SignalingCodec.encode(
                    SignalingWireMessage.HelloSig(roomKey = roomKey, peerId = peerId),
                )
                webSocket.send(hello)
                helloSent.set(true)
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try {
                    SignalingCodec.decode(text)
                } catch (_: Exception) {
                    return
                }
                when (msg) {
                    is SignalingWireMessage.Signal -> {
                        if (msg.to == peerId) {
                            listenerRef.get()?.onSignal(msg.from, msg.payload)
                        }
                    }
                    is SignalingWireMessage.Error -> {
                        listenerRef.get()?.onSignalingFailed(msg.reason)
                    }
                    is SignalingWireMessage.HelloSig -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                openLatch.countDown()
                if (!closed.get()) {
                    listenerRef.get()?.onSignalingFailed(
                        t.message?.takeIf { it.isNotBlank() } ?: "signaling_unreachable",
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get() && code != 1000) {
                    listenerRef.get()?.onSignalingFailed(reason.ifBlank { "closed" })
                }
            }
        })
        socketRef.set(ws)
        val opened = openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!opened || !helloSent.get()) {
            ws.cancel()
            socketRef.set(null)
            error("signaling_unreachable")
        }
    }

    override suspend fun sendSignal(toPeerId: String, payload: String) {
        val ws = socketRef.get() ?: error("not connected")
        val ok = ws.send(
            SignalingCodec.encode(
                SignalingWireMessage.Signal(from = peerId, to = toPeerId, payload = payload),
            ),
        )
        if (!ok) {
            listenerRef.get()?.onSignalingFailed("send_failed")
        }
    }

    override fun setListener(listener: SignalingChannel.Listener?) {
        listenerRef.set(listener)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listenerRef.set(null)
        socketRef.getAndSet(null)?.close(1000, "bye")
    }

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
