package com.mofy.app.watchtogether.transport

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeTransportTest {

    @Test
    fun `send A to B delivers to listener`() = runBlocking {
        val transport = FakeWtTransport()
        val received = mutableListOf<Pair<String, String>>()
        transport.setListener(object : WtTransport.Listener {
            override fun onMessage(fromPeerId: String, json: String) {
                received += fromPeerId to json
            }
            override fun onPeerConnected(peerId: String) = Unit
            override fun onPeerDisconnected(peerId: String) = Unit
            override fun onTransportFailed(reason: String) = Unit
        })

        transport.send("guest-1", """{"type":"seek"}""")
        transport.deliver("guest-1", """{"type":"pause"}""")

        assertEquals(listOf(FakeWtTransport.Outbound("guest-1", """{"type":"seek"}""")), transport.sent)
        assertEquals(listOf("guest-1" to """{"type":"pause"}"""), received)
    }

    @Test
    fun `sendToAll fans out excluding optional peer`() = runBlocking {
        val transport = FakeWtTransport()
        transport.connectPeer("a")
        transport.connectPeer("b")
        transport.connectPeer("c")

        transport.sendToAll("""{"type":"play"}""", excludePeerId = "b")

        assertEquals(
            listOf(
                FakeWtTransport.Outbound("a", """{"type":"play"}"""),
                FakeWtTransport.Outbound("c", """{"type":"play"}"""),
            ),
            transport.sent,
        )
        assertEquals(listOf("""{"type":"play"}""" to "b"), transport.sentToAll)
    }

    @Test
    fun `peer connect disconnect and fail notify listener`() {
        val transport = FakeWtTransport()
        val events = mutableListOf<String>()
        transport.setListener(object : WtTransport.Listener {
            override fun onMessage(fromPeerId: String, json: String) = Unit
            override fun onPeerConnected(peerId: String) {
                events += "up:$peerId"
            }
            override fun onPeerDisconnected(peerId: String) {
                events += "down:$peerId"
            }
            override fun onTransportFailed(reason: String) {
                events += "fail:$reason"
            }
        })

        transport.connectPeer("g1")
        transport.disconnectPeer("g1")
        transport.fail("ice_failed")

        assertEquals(listOf("up:g1", "down:g1", "fail:ice_failed"), events)
    }

    @Test
    fun `signaling send and deliver round-trip`() = runBlocking {
        val channel = FakeSignalingChannel()
        val received = mutableListOf<Pair<String, String>>()
        channel.setListener(object : SignalingChannel.Listener {
            override fun onSignal(fromPeerId: String, payload: String) {
                received += fromPeerId to payload
            }
            override fun onSignalingFailed(reason: String) = Unit
        })

        channel.sendSignal("host", "sdp-offer")
        channel.deliver("host", "sdp-answer")

        assertEquals(listOf(FakeSignalingChannel.Outbound("host", "sdp-offer")), channel.sent)
        assertEquals(listOf("host" to "sdp-answer"), received)
    }

    @Test
    fun `closed transport rejects further use`() {
        val transport = FakeWtTransport()
        transport.close()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { transport.send("x", "{}") }
        }
        assertTrue(transport.connectedPeers().isEmpty())
    }
}
