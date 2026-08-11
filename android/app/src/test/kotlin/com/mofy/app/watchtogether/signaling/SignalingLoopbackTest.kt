package com.mofy.app.watchtogether.signaling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SignalingLoopbackTest {

    private var server: EmbeddedSignalingServer? = null
    private val channels = mutableListOf<OkHttpSignalingChannel>()

    @AfterEach
    fun tearDown() {
        channels.forEach { it.close() }
        channels.clear()
        server?.stop()
        server = null
        SignalingSettings.relayBaseUrl = null
    }

    @Test
    fun `hello-sig and signal payload both ways on loopback`() {
        val srv = EmbeddedSignalingServer().also { server = it }
        val port = srv.start()
        assertTrue(port > 0)

        val roomKey = "7FK9Q2"
        val url = srv.localUrl(roomKey)

        val hostInbox = CopyOnWriteArrayList<Pair<String, String>>()
        val guestInbox = CopyOnWriteArrayList<Pair<String, String>>()
        val guestGot = CountDownLatch(1)
        val hostGot = CountDownLatch(1)

        val host = OkHttpSignalingChannel(url, peerId = "host", roomKey = roomKey).also { channels += it }
        host.setListener(object : com.mofy.app.watchtogether.transport.SignalingChannel.Listener {
            override fun onSignal(fromPeerId: String, payload: String) {
                hostInbox += fromPeerId to payload
                hostGot.countDown()
            }
            override fun onSignalingFailed(reason: String) = Unit
        })
        host.connect()

        val guest = OkHttpSignalingChannel(url, peerId = "guest-1", roomKey = roomKey).also { channels += it }
        guest.setListener(object : com.mofy.app.watchtogether.transport.SignalingChannel.Listener {
            override fun onSignal(fromPeerId: String, payload: String) {
                guestInbox += fromPeerId to payload
                guestGot.countDown()
            }
            override fun onSignalingFailed(reason: String) = Unit
        })
        guest.connect()

        runBlocking {
            host.sendSignal("guest-1", "sdp-offer-abc")
        }
        assertTrue(guestGot.await(3, TimeUnit.SECONDS), "guest did not receive signal")
        assertEquals(listOf("host" to "sdp-offer-abc"), guestInbox.toList())

        runBlocking {
            guest.sendSignal("host", "sdp-answer-xyz")
        }
        assertTrue(hostGot.await(3, TimeUnit.SECONDS), "host did not receive signal")
        assertEquals(listOf("guest-1" to "sdp-answer-xyz"), hostInbox.toList())
    }

    @Test
    fun `wrong roomKey on hello is rejected`() {
        val srv = EmbeddedSignalingServer().also { server = it }
        srv.start()
        val url = srv.localUrl("7FK9Q2")

        val failed = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        val guest = OkHttpSignalingChannel(url, peerId = "guest-1", roomKey = "AAAAAA")
            .also { channels += it }
        guest.setListener(object : com.mofy.app.watchtogether.transport.SignalingChannel.Listener {
            override fun onSignal(fromPeerId: String, payload: String) = Unit
            override fun onSignalingFailed(reason: String) {
                failed += reason
                latch.countDown()
            }
        })
        guest.connect()
        assertTrue(latch.await(3, TimeUnit.SECONDS), "expected wrong_room failure")
        assertTrue(failed.any { it.contains("wrong_room") }, failed.toString())
    }

    @Test
    fun `bad path fails connect or reports signaling failure`() {
        val srv = EmbeddedSignalingServer().also { server = it }
        srv.start()
        val badUrl = "ws://127.0.0.1:${srv.port}/not-wt/7FK9Q2"

        val failed = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        val ch = OkHttpSignalingChannel(badUrl, peerId = "x", roomKey = "7FK9Q2")
            .also { channels += it }
        ch.setListener(object : com.mofy.app.watchtogether.transport.SignalingChannel.Listener {
            override fun onSignal(fromPeerId: String, payload: String) = Unit
            override fun onSignalingFailed(reason: String) {
                failed += reason
                latch.countDown()
            }
        })
        try {
            ch.connect(timeoutMs = 2_000)
            assertTrue(latch.await(3, TimeUnit.SECONDS), "expected failure on bad path")
            assertTrue(failed.isNotEmpty(), failed.toString())
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("signaling") == true, e.message)
        }
    }

    @Test
    fun `SignalingSettings urlForRoom appends path`() {
        SignalingSettings.relayBaseUrl = "wss://mofy-sig.fly.dev/"
        assertEquals(
            "wss://mofy-sig.fly.dev/wt/7FK9Q2",
            SignalingSettings.urlForRoom("7FK9Q2"),
        )
    }

    @Test
    fun `parseRoomKey extracts key from resource descriptor`() {
        assertEquals("7FK9Q2", EmbeddedSignalingServer.parseRoomKey("/wt/7FK9Q2"))
        assertEquals("7FK9Q2", EmbeddedSignalingServer.parseRoomKey("/wt/7FK9Q2?x=1"))
        assertEquals(null, EmbeddedSignalingServer.parseRoomKey("/other/7FK9Q2"))
    }
}
