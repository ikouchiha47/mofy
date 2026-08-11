package com.mofy.app.watchtogether.signaling

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Minimal multi-room WebSocket hub for SDP/ICE bootstrap (ADR 0006 B7).
 *
 * Path: `/wt/{roomKey}`. Clients must send [SignalingWireMessage.HelloSig]
 * first; then [SignalingWireMessage.Signal] frames are routed by `to` peer id.
 *
 * Same class runs embedded in the host app (LAN) or later as a standalone
 * process on Fly — protocol does not change.
 */
class EmbeddedSignalingServer(
    private val bindHost: String = "0.0.0.0",
    private val bindPort: Int = 0,
) {
    private val started = CountDownLatch(1)
    private val rooms = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocket>>()
    private var server: WebSocketServer? = null

    val port: Int
        get() = server?.port ?: -1

    fun start(timeoutMs: Long = 5_000): Int {
        check(server == null) { "already started" }
        val ws = object : WebSocketServer(InetSocketAddress(bindHost, bindPort)) {
            override fun onStart() {
                started.countDown()
            }

            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                val roomKey = parseRoomKey(handshake.resourceDescriptor)
                if (roomKey == null) {
                    conn.send(SignalingCodec.encode(SignalingWireMessage.Error("bad_path")))
                    conn.close(1008, "bad_path")
                    return
                }
                conn.setAttachment(PeerState(roomKey = roomKey, peerId = null))
            }

            override fun onMessage(conn: WebSocket, message: String) {
                val state = conn.getAttachment<PeerState>() ?: run {
                    conn.close(1011, "no_state")
                    return
                }
                val msg = try {
                    SignalingCodec.decode(message)
                } catch (_: Exception) {
                    conn.send(SignalingCodec.encode(SignalingWireMessage.Error("bad_json")))
                    return
                }
                when (msg) {
                    is SignalingWireMessage.HelloSig -> handleHello(conn, state, msg)
                    is SignalingWireMessage.Signal -> handleSignal(conn, state, msg)
                    is SignalingWireMessage.Error -> Unit
                }
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                val state = conn.getAttachment<PeerState>() ?: return
                val peerId = state.peerId ?: return
                rooms[state.roomKey]?.remove(peerId, conn)
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                // Leave socket handling to the library; tests assert protocol paths.
            }
        }
        ws.isReuseAddr = true
        server = ws
        ws.start()
        check(started.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "signaling server failed to start within ${timeoutMs}ms"
        }
        return ws.port
    }

    fun stop() {
        val ws = server ?: return
        server = null
        rooms.clear()
        ws.stop(1_000)
    }

    /** Loopback URL for tests / local experiment. */
    fun localUrl(roomKey: String): String = "ws://127.0.0.1:$port/wt/$roomKey"

    /**
     * Build a guest-facing URL. [hostAddress] should be a LAN IP the guest can
     * reach (or the public relay host when this process is the relay).
     */
    fun urlFor(roomKey: String, hostAddress: String): String =
        "ws://$hostAddress:$port/wt/$roomKey"

    private fun handleHello(conn: WebSocket, state: PeerState, msg: SignalingWireMessage.HelloSig) {
        if (msg.roomKey != state.roomKey) {
            conn.send(SignalingCodec.encode(SignalingWireMessage.Error("wrong_room")))
            conn.close(1008, "wrong_room")
            return
        }
        if (msg.peerId.isBlank()) {
            conn.send(SignalingCodec.encode(SignalingWireMessage.Error("bad_peer")))
            conn.close(1008, "bad_peer")
            return
        }
        val room = rooms.getOrPut(state.roomKey) { ConcurrentHashMap() }
        val previous = room.put(msg.peerId, conn)
        previous?.close(1000, "replaced")
        conn.setAttachment(state.copy(peerId = msg.peerId))
    }

    private fun handleSignal(conn: WebSocket, state: PeerState, msg: SignalingWireMessage.Signal) {
        val from = state.peerId
        if (from == null) {
            conn.send(SignalingCodec.encode(SignalingWireMessage.Error("hello_required")))
            return
        }
        if (msg.from != from) {
            conn.send(SignalingCodec.encode(SignalingWireMessage.Error("from_mismatch")))
            return
        }
        val target = rooms[state.roomKey]?.get(msg.to)
        if (target == null || target.isClosed) {
            conn.send(SignalingCodec.encode(SignalingWireMessage.Error("peer_not_found")))
            return
        }
        target.send(SignalingCodec.encode(msg))
    }

    private data class PeerState(val roomKey: String, val peerId: String?)

    companion object {
        private val pathPattern = Regex("^/wt/([^/?#]+)")

        fun parseRoomKey(resourceDescriptor: String?): String? {
            if (resourceDescriptor == null) return null
            val match = pathPattern.find(resourceDescriptor) ?: return null
            return match.groupValues[1]
        }
    }
}
