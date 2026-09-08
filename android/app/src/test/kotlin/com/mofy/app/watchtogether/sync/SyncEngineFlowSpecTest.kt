package com.mofy.app.watchtogether.sync

import com.mofy.app.playback.FakePlayerController
import com.mofy.app.playback.PlayerController
import com.mofy.app.watchtogether.Participant
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.protocol.WtMessage
import com.mofy.app.watchtogether.protocol.WtMessageCodec
import com.mofy.app.watchtogether.sync.SyncEngineConfig.CONTROL_DEBOUNCE_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.CONTROL_IGNORE_WINDOW_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.DURATION_MISMATCH_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.HOST_DISCONNECT_GRACE_MS
import com.mofy.app.watchtogether.sync.SyncEngineConfig.HOST_PEER_ID
import com.mofy.app.watchtogether.transport.FakeWtTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for docs/tasks/watch-together-flows.md.
 * Expected RED against current SyncEngine until the engine matches the doc.
 * These are NOT regression tests - they are a contract the engine has not
 * caught up to yet.
 */
class SyncEngineFlowSpecTest {

    private val roomKey = "7FK9Q2"
    private val itemHash = "abc123def4567890"

    // Injected, deterministic clock - never Thread.sleep.
    private var nowMs = 1_000_000L
    private val clock: () -> Long = { nowMs }

    /**
     * A player double that matches real VlcPlayerController's actual
     * behavior after release(): any property read or method call throws,
     * it does not silently return stale/zero values. FakePlayerController
     * does not model this (it just freezes state), which is why the real
     * device crashes were never caught by existing unit tests.
     */
    private class ThrowingAfterReleasePlayerController : PlayerController {
        private var released = false
        var positionMsBacking = 0L
        var isPlayingBacking = false

        override val positionMs: Long
            get() { if (released) error("can't get VLCObject instance"); return positionMsBacking }
        override val isPlaying: Boolean
            get() { if (released) error("can't get VLCObject instance"); return isPlayingBacking }
        override val durationMs: Long = 100_000L

        override fun play() { if (released) error("released"); isPlayingBacking = true }
        override fun pause() { if (released) error("released"); isPlayingBacking = false }
        override fun seekTo(positionMs: Long) { if (released) error("released"); positionMsBacking = positionMs }
        override fun setSubtitleTrack(index: Int?) { if (released) error("released") }
        override fun setAudioTrack(index: Int?) { if (released) error("released") }
        override fun release() { released = true }
        override fun setVideoScale(scale: com.mofy.app.playback.VideoScale) {}
    }

    // --- §3 virtual clock ------------------------------------------------

    @Test
    fun `SE-VCLK-01 heartbeat after host player released must broadcast last position not 0`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(42_000)
        player.play()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        player.release()
        host.heartbeatTick()

        val pos = messages(transport).filterIsInstance<WtMessage.Position>().single()
        assertEquals(42_000, pos.positionMs, "heartbeat must broadcast the virtual clock, not a released player's 0")
        assertTrue(pos.isPlaying, "released host player must not broadcast isPlaying=false")
    }

    @Test
    fun `SE-VCLK-02 rebind after release must resume at last position not 0`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(77_000)
        player.play()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()

        player.release()
        val newPlayer = ThrowingAfterReleasePlayerController()
        host.rebindPlayer(newPlayer)

        assertEquals(77_000, newPlayer.positionMsBacking, "rebind must not seek to 0")
        assertTrue(newPlayer.isPlayingBacking, "rebind must resume playing to match virtual isPlaying")
    }

    @Test
    fun `SE-VCLK-03 playing unbound host clock advances`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(42_000)
        player.play()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        player.release()
        nowMs += 5_000
        host.heartbeatTick()

        val pos = messages(transport).filterIsInstance<WtMessage.Position>().single()
        assertEquals(47_000, pos.positionMs)
        assertTrue(pos.isPlaying)
    }

    @Test
    fun `SE-VCLK-04 paused unbound host clock is frozen`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(42_000)
        player.pause()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        player.release()
        nowMs += 5_000
        host.heartbeatTick()

        val pos = messages(transport).filterIsInstance<WtMessage.Position>().single()
        assertEquals(42_000, pos.positionMs)
        assertFalse(pos.isPlaying)
    }

    @Test
    fun `SE-VCLK-05 remote seek updates unbound host clock`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(10_000)
        player.play()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        player.release()
        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Seek(positionMs = 55_000, by = "guest-1")))
        host.heartbeatTick()

        val pos = messages(transport).filterIsInstance<WtMessage.Position>().single()
        assertEquals(55_000, pos.positionMs)
        assertTrue(pos.isPlaying)
    }

    @Test
    fun `SE-VCLK-06 remote pause updates unbound host clock`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(10_000)
        player.play()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        player.release()
        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Pause(positionMs = 10_000, by = "guest-1")))
        nowMs += 5_000
        host.heartbeatTick()

        val pos = messages(transport).filterIsInstance<WtMessage.Position>().single()
        assertEquals(10_000, pos.positionMs)
        assertFalse(pos.isPlaying)
    }

    @Test
    fun `SE-VCLK-07 rebind while paused must not play`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(20_000)
        player.pause()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()

        player.release()
        val newPlayer = ThrowingAfterReleasePlayerController()
        host.rebindPlayer(newPlayer)

        assertEquals(20_000, newPlayer.positionMsBacking)
        assertFalse(newPlayer.isPlayingBacking, "rebind must preserve paused state, not call play()")
    }

    @Test
    fun `SE-VCLK-08 join-ack after host release uses virtual clock`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(33_000)
        player.play()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()

        player.release()
        transport.connectPeer("g2")
        transport.deliver("g2", WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash)))

        val ack = outboundTo(transport, "g2")
            .filterIsInstance<WtMessage.JoinAck>()
            .single()
        assertEquals(33_000, ack.positionMs, "hot-join snapshot must reflect virtual clock, not 0")
        assertTrue(ack.isPlaying)
    }

    @Test
    fun `SE-VCLK-09 guest released player inbound seek is remembered on rebind`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(5_000)
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)
        transport.sent.clear()

        player.release()
        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Seek(positionMs = 90_000, by = "host-1")))

        val outboundSeeks = messages(transport).filterIsInstance<WtMessage.Seek>()
        assertTrue(outboundSeeks.isEmpty(), "guest must not echo a host seek back out")

        val newPlayer = ThrowingAfterReleasePlayerController()
        guest.rebindPlayer(newPlayer)
        assertEquals(90_000, newPlayer.positionMsBacking, "re-enter must seek to last applied host-fanout, not 0")
    }

    @Test
    fun `SE-VCLK-10 host localPause after release must emit pause at last position not 0`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(42_000)
        player.play()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        player.release()
        host.localPause()

        val pauses = messages(transport).filterIsInstance<WtMessage.Pause>()
        assertEquals(1, pauses.size)
        assertEquals(42_000, pauses.single().positionMs, "headless host pausing must pause the room at 42000, not 0")
    }

    @Test
    fun `SE-VCLK-11 guest heartbeatTick is a no-op`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)
        transport.sent.clear()

        guest.heartbeatTick()

        val positions = messages(transport).filterIsInstance<WtMessage.Position>()
        assertTrue(positions.isEmpty(), "guest must never broadcast a position heartbeat")
    }

    // --- §4 control plane -----------------------------------------------

    @Test
    fun `SE-CTL-01 scrub burst emits nothing until debounce flush, then last seek`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L).forEach { host.localSeek(it) }

        val beforeFlush = messages(transport).filterIsInstance<WtMessage.Seek>()
        assertTrue(beforeFlush.isEmpty(), "scrub-end/debounce: no per-frame seek until the window flushes")

        nowMs += CONTROL_DEBOUNCE_MS
        host.tick()

        val seeks = messages(transport).filterIsInstance<WtMessage.Seek>()
        assertEquals(1, seeks.size, "flush must emit exactly one seek")
        assertEquals(5_000, seeks.single().positionMs, "last scrub position wins")
    }

    @Test
    fun `SE-CTL-02 after debounce window a new seek is a separate emit`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        host.localSeek(5_000)
        nowMs += CONTROL_DEBOUNCE_MS
        host.tick()
        transport.sent.clear()

        host.localSeek(8_000)
        val beforeFlush = messages(transport).filterIsInstance<WtMessage.Seek>()
        assertTrue(beforeFlush.isEmpty(), "the new scrub is also debounced")

        nowMs += CONTROL_DEBOUNCE_MS
        host.tick()
        val seeks = messages(transport).filterIsInstance<WtMessage.Seek>()
        assertEquals(1, seeks.size)
        assertEquals(8_000, seeks.single().positionMs)
    }

    @Test
    fun `SE-CTL-03 pending replace host fans only the latest of two guest seeks`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.connectPeer("g2")
        transport.sent.clear()

        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Seek(positionMs = 10_000, by = "guest-1")))
        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Seek(positionMs = 80_000, by = "guest-1")))

        val fanToG2 = outboundTo(transport, "g2").filterIsInstance<WtMessage.Seek>()
        assertEquals(1, fanToG2.size, "host must fan out only the newest seek, not both")
        assertEquals(80_000, fanToG2.single().positionMs)

        val fanToG1 = outboundTo(transport, "g1").filterIsInstance<WtMessage.Seek>()
        assertTrue(fanToG1.isEmpty(), "originator must be echo-suppressed")
    }

    @Test
    fun `SE-CTL-04 play is echo-suppressed to the originator`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.connectPeer("g2")
        transport.sent.clear()

        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Play(positionMs = 5_000, by = "guest-1")))

        val toG2 = outboundTo(transport, "g2").filterIsInstance<WtMessage.Play>()
        assertEquals(1, toG2.size)
        val toG1 = outboundTo(transport, "g1").filterIsInstance<WtMessage.Play>()
        assertTrue(toG1.isEmpty(), "no play echo back to the originator")
    }

    @Test
    fun `SE-CTL-05 ignore window drops local slider echo after a remote seek`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(0)
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)
        transport.sent.clear()

        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Seek(positionMs = 90_000, by = "host-1")))
        assertEquals(90_000, player.positionMsBacking)

        nowMs += 100 // < CONTROL_IGNORE_WINDOW_MS
        guest.localSeek(10_000)
        assertEquals(90_000, player.positionMsBacking, "local slider must not override a just-applied remote seek")
        assertTrue(
            messages(transport).filterIsInstance<WtMessage.Seek>().isEmpty(),
            "must not emit a seek that would ping-pong the room",
        )

        nowMs += CONTROL_IGNORE_WINDOW_MS
        guest.tick()
        guest.localSeek(10_000)
        nowMs += CONTROL_DEBOUNCE_MS
        guest.tick()
        assertEquals(10_000, player.positionMsBacking, "after the ignore window a local scrub applies")
        val outbound = messages(transport).filterIsInstance<WtMessage.Seek>()
        assertEquals(1, outbound.size)
        assertEquals(10_000, outbound.single().positionMs)
    }

    @Test
    fun `SE-CTL-06 stale Position must not override a newer Seek`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)

        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Seek(positionMs = 90_000, by = "host-1", ts = 10L)))
        assertEquals(90_000, player.positionMsBacking)

        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Position(positionMs = 40_000, isPlaying = true, ts = 1L)))
        assertEquals(90_000, player.positionMsBacking, "a stale heartbeat must not stomp a newer seek")
    }

    @Test
    fun `SE-CTL-07 stale Position must not override a newer Pause`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(50_000)
        player.play()
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)

        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Pause(positionMs = 50_000, by = "host-1")))
        assertFalse(player.isPlayingBacking)

        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Position(positionMs = 60_000, isPlaying = true, ts = 1L)))
        assertFalse(player.isPlayingBacking, "a stale heartbeat must not re-play a paused room")
        assertEquals(50_000, player.positionMsBacking, "stale heartbeat must not seek off the pause position")
    }

    @Test
    fun `SE-CTL-08 newer Position after Seek still applies`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)

        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Seek(positionMs = 90_000, by = "host-1")))
        nowMs += 5_000
        transport.deliver(HOST_PEER_ID, WtMessageCodec.encode(WtMessage.Position(positionMs = 95_000, isPlaying = true, ts = nowMs)))

        assertEquals(95_000, player.positionMsBacking)
        assertTrue(player.isPlayingBacking)
    }

    @Test
    fun `SE-CTL-09 pause is not coalesced away`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.sent.clear()

        host.localPause()
        host.localPause()

        val pauses = messages(transport).filterIsInstance<WtMessage.Pause>()
        assertTrue(pauses.isNotEmpty(), "play/pause is a control, not a scrub; it must not be debounced away")
    }

    @Test
    fun `SE-CTL-10 host assigns ts on fan-out not guest wall clock`() {
        val player = ThrowingAfterReleasePlayerController()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")
        transport.connectPeer("g2")
        transport.sent.clear()

        nowMs = 5_000_000L
        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Seek(positionMs = 12_000, by = "guest-1")))

        // A lone relay seek is still debounced like any other seek (one
        // code path, no special-casing) - it flushes on tick(), stamped
        // with the host's clock at flush time, not at receipt time.
        nowMs += CONTROL_DEBOUNCE_MS
        host.tick()

        val fanToG2 = outboundTo(transport, "g2").filterIsInstance<WtMessage.Seek>()
        assertEquals(1, fanToG2.size)
        assertEquals(12_000, fanToG2.single().positionMs)
        assertEquals(nowMs, fanToG2.single().ts, "host must stamp its own clock on the fan-out")
        assertNotNull(fanToG2.single().seq, "host must assign seq so heartbeats can lose to newer controls")
    }

    @Test
    fun `SE-CTL-11 guest localSeek after release does not crash and uses scrub position`() {
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(15_000)
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)
        transport.sent.clear()

        player.release()
        guest.localSeek(40_000)

        val seeks = messages(transport).filterIsInstance<WtMessage.Seek>()
        assertEquals(1, seeks.size)
        assertEquals(40_000, seeks.single().positionMs)
    }

    // --- §5 network: flap vs dead ---------------------------------------

    @Test
    fun `SE-NET-01 guest host disconnect must not HostLost immediately`() {
        val events = mutableListOf<SyncEngine.SyncEvent>()
        val transport = FakeWtTransport()
        val guest = guestEngine(ThrowingAfterReleasePlayerController(), transport, events = events)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)

        transport.disconnectPeer(HOST_PEER_ID)

        assertFalse(
            events.contains(SyncEngine.SyncEvent.HostLost),
            "host disconnect must be treated as a flap inside grace, not host-dead",
        )
    }

    @Test
    fun `SE-NET-02 HostLost fires after grace, exactly once, not before`() {
        val events = mutableListOf<SyncEngine.SyncEvent>()
        val transport = FakeWtTransport()
        val guest = guestEngine(ThrowingAfterReleasePlayerController(), transport, events = events)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)

        transport.disconnectPeer(HOST_PEER_ID)
        assertFalse(events.contains(SyncEngine.SyncEvent.HostLost), "must not fire before grace elapses")

        nowMs += HOST_DISCONNECT_GRACE_MS
        guest.tick()
        assertTrue(events.contains(SyncEngine.SyncEvent.HostLost), "must fire after grace elapses")

        guest.tick()
        assertEquals(1, events.count { it == SyncEngine.SyncEvent.HostLost }, "HostLost must fire exactly once")
    }

    @Test
    fun `SE-NET-03 reconnect inside grace cancels the demote and resyncs from snapshot`() {
        val events = mutableListOf<SyncEngine.SyncEvent>()
        val player = ThrowingAfterReleasePlayerController()
        player.seekTo(12_000)
        player.play()
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport, events = events)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)

        transport.disconnectPeer(HOST_PEER_ID)
        nowMs += HOST_DISCONNECT_GRACE_MS / 2
        guest.tick()
        assertFalse(events.contains(SyncEngine.SyncEvent.HostLost))

        transport.connectPeer(HOST_PEER_ID)
        transport.deliver(
            HOST_PEER_ID,
            WtMessageCodec.encode(
                WtMessage.JoinAck(
                    participantId = "guest-temp",
                    participants = listOf(
                        Participant("host-1", "Alex", Role.HOST),
                        Participant("guest-temp", "Priya", Role.GUEST),
                    ),
                    positionMs = 44_000,
                    isPlaying = true,
                ),
            ),
        )
        assertEquals(44_000, player.positionMsBacking, "reconnect snapshot must seek like hot-join")
        assertTrue(player.isPlayingBacking)

        nowMs += HOST_DISCONNECT_GRACE_MS
        guest.tick()
        assertFalse(events.contains(SyncEngine.SyncEvent.HostLost), "reconnect inside grace must cancel demote")
    }

    @Test
    fun `SE-NET-04 guest flap does not kill the room`() {
        val events = mutableListOf<SyncEngine.SyncEvent>()
        val transport = FakeWtTransport()
        val host = hostEngine(ThrowingAfterReleasePlayerController(), transport, events = events)
        host.start()
        transport.connectPeer("g1")
        transport.connectPeer("g2")
        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash)))
        transport.deliver("g2", WtMessageCodec.encode(WtMessage.Join(roomKey, "Sam", itemHash)))
        transport.sent.clear()

        transport.disconnectPeer("g1")

        assertFalse(events.contains(SyncEngine.SyncEvent.HostLost), "a guest drop is not host death")
        assertEquals(2, host.participants().size, "host + remaining guest")
        val left = outboundTo(transport, "g2").filterIsInstance<WtMessage.ParticipantEvent>()
            .filter { it.op == WtMessage.ParticipantEvent.Op.LEFT }
        assertEquals(1, left.size)
    }

    @Test
    fun `SE-NET-05 transport fail is Error not HostLost`() {
        val events = mutableListOf<SyncEngine.SyncEvent>()
        val transport = FakeWtTransport()
        val guest = guestEngine(ThrowingAfterReleasePlayerController(), transport, events = events)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)

        transport.fail("ice failed")

        assertTrue(events.contains(SyncEngine.SyncEvent.Error("ice failed")))
        assertFalse(events.contains(SyncEngine.SyncEvent.HostLost), "a failed P2P attempt is not host death")
    }

    @Test
    fun `SE-NET-06 guest ignores disconnect of a non-host peer`() {
        val events = mutableListOf<SyncEngine.SyncEvent>()
        val transport = FakeWtTransport()
        val guest = guestEngine(ThrowingAfterReleasePlayerController(), transport, events = events)
        guest.start()
        transport.connectPeer(HOST_PEER_ID)
        transport.connectPeer("other")

        transport.disconnectPeer("other")

        assertFalse(events.contains(SyncEngine.SyncEvent.HostLost))
    }

    // --- join duration / snapshot ---------------------------------------

    private fun hostFake(durationMs: Long) = FakePlayerController(initialDurationMs = durationMs)

    @Test
    fun `SE-JOIN-01 duration mismatch rejects join`() {
        val hostPlayer = hostFake(6_000_000L)
        val transport = FakeWtTransport()
        val host = hostEngine(hostPlayer, transport)
        host.start()
        transport.connectPeer("g1")

        transport.deliver(
            "g1",
            WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash, durationMs = 6_000_000L + DURATION_MISMATCH_MS + 1)),
        )

        val err = outboundTo(transport, "g1").filterIsInstance<WtMessage.Error>()
        assertEquals(1, err.size)
        assertTrue(err.single().reason.contains("duration", ignoreCase = true), err.single().reason)
        assertEquals(1, host.participants().size, "mismatched cut must not join")
    }

    @Test
    fun `SE-JOIN-02 duration within gate is accepted`() {
        val hostPlayer = hostFake(6_000_000L)
        val transport = FakeWtTransport()
        val host = hostEngine(hostPlayer, transport)
        host.start()
        transport.connectPeer("g1")

        transport.deliver(
            "g1",
            WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash, durationMs = 6_000_000L + DURATION_MISMATCH_MS - 1)),
        )

        val acks = outboundTo(transport, "g1").filterIsInstance<WtMessage.JoinAck>()
        assertEquals(1, acks.size)
        assertEquals(2, host.participants().size)
    }

    @Test
    fun `SE-JOIN-03 null duration is accepted for compat`() {
        val hostPlayer = hostFake(6_000_000L)
        val transport = FakeWtTransport()
        val host = hostEngine(hostPlayer, transport)
        host.start()
        transport.connectPeer("g1")

        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash)))

        val acks = outboundTo(transport, "g1").filterIsInstance<WtMessage.JoinAck>()
        assertEquals(1, acks.size)
        assertEquals(2, host.participants().size)
    }

    @Test
    fun `SE-JOIN-04 hot-join snapshot while playing`() {
        val hostPlayer = hostFake(100_000L)
        hostPlayer.seekTo(12_345)
        hostPlayer.play()
        val transport = FakeWtTransport()
        val host = hostEngine(hostPlayer, transport)
        host.start()
        transport.connectPeer("g1")

        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash)))

        val ack = outboundTo(transport, "g1").filterIsInstance<WtMessage.JoinAck>()
            .single()
        assertEquals(12_345, ack.positionMs)
        assertTrue(ack.isPlaying)
    }

    @Test
    fun `SE-JOIN-06 join-ack after host paused`() {
        val hostPlayer = hostFake(100_000L)
        hostPlayer.seekTo(8_000)
        val transport = FakeWtTransport()
        val host = hostEngine(hostPlayer, transport)
        host.start()
        transport.connectPeer("g1")

        transport.deliver("g1", WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash)))

        val ack = outboundTo(transport, "g1").filterIsInstance<WtMessage.JoinAck>()
            .single()
        assertEquals(8_000, ack.positionMs)
        assertFalse(ack.isPlaying)
    }

    // --- helpers ---------------------------------------------------------

    private fun messages(transport: FakeWtTransport): List<WtMessage> =
        transport.sent.map { WtMessageCodec.decode(it.json) }

    private fun outboundTo(transport: FakeWtTransport, peerId: String): List<WtMessage> =
        transport.sent.filter { it.peerId == peerId }.map { WtMessageCodec.decode(it.json) }

    private fun hostEngine(
        player: PlayerController,
        transport: FakeWtTransport,
        events: MutableList<SyncEngine.SyncEvent>? = null,
    ) = SyncEngine(
        role = Role.HOST,
        roomKey = roomKey,
        itemHash = itemHash,
        localParticipant = Participant("host-1", "Alex", Role.HOST),
        player = player,
        transport = transport,
        clock = clock,
        events = events?.let { list ->
            SyncEngine.Listener { event -> list.add(event) }
        },
    )

    private fun guestEngine(
        player: PlayerController,
        transport: FakeWtTransport,
        id: String = "guest-temp",
        events: MutableList<SyncEngine.SyncEvent>? = null,
    ) = SyncEngine(
        role = Role.GUEST,
        roomKey = roomKey,
        itemHash = itemHash,
        localParticipant = Participant(id, "Priya", Role.GUEST),
        player = player,
        transport = transport,
        clock = clock,
        events = events?.let { list ->
            SyncEngine.Listener { event -> list.add(event) }
        },
    )
}