package com.mofy.app.watchtogether.sync

import com.mofy.app.playback.FakePlayerController
import com.mofy.app.watchtogether.Participant
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.SessionLimits
import com.mofy.app.watchtogether.protocol.WtMessage
import com.mofy.app.watchtogether.protocol.WtMessageCodec
import com.mofy.app.watchtogether.sync.SyncEngineConfig.HOST_PEER_ID
import com.mofy.app.watchtogether.transport.FakeWtTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncEngineTest {

    private val roomKey = "7FK9Q2"
    private val itemHash = "abc123def4567890"

    @Test
    fun `1 host local seek emits one seek and player at position`() {
        val player = FakePlayerController()
        val transport = FakeWtTransport()
        val host = hostEngine(player, transport)
        host.start()
        transport.connectPeer("g1")

        host.localSeek(12_345)

        assertEquals(12_345, player.positionMs)
        val seeks = transport.sent.map { WtMessageCodec.decode(it.json) }.filterIsInstance<WtMessage.Seek>()
        assertEquals(1, seeks.size)
        assertEquals(12_345, seeks.single().positionMs)
        assertEquals("host-1", seeks.single().by)
    }

    @Test
    fun `2 guest receives seek applies locally with zero outbound seek`() {
        val player = FakePlayerController()
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.sent.clear()

        transport.deliver(
            HOST_PEER_ID,
            WtMessageCodec.encode(WtMessage.Seek(positionMs = 50_000, by = "host-1")),
        )

        assertEquals(50_000, player.positionMs)
        assertEquals(50_000, player.lastSeekMs)
        val outboundSeeks = transport.sent
            .map { WtMessageCodec.decode(it.json) }
            .filterIsInstance<WtMessage.Seek>()
        assertTrue(outboundSeeks.isEmpty())
    }

    @Test
    fun `3 guest local pause reaches host which applies and fans out`() {
        val hostPlayer = FakePlayerController()
        val hostTx = FakeWtTransport()
        val host = hostEngine(hostPlayer, hostTx)
        host.start()
        hostTx.connectPeer("g1")
        hostTx.connectPeer("g2")

        val guestPlayer = FakePlayerController()
        guestPlayer.seekTo(8_000)
        guestPlayer.play()
        val guestTx = FakeWtTransport()
        val guest = guestEngine(guestPlayer, guestTx, id = "guest-temp")
        guest.start()
        guestTx.sent.clear()

        guest.localPause()

        val toHost = guestTx.sent.single()
        assertEquals(HOST_PEER_ID, toHost.peerId)
        val pause = WtMessageCodec.decode(toHost.json) as WtMessage.Pause
        assertEquals(8_000, pause.positionMs)

        hostTx.deliver("g1", toHost.json)

        assertFalse(hostPlayer.isPlaying)
        assertEquals(8_000, hostPlayer.positionMs)

        val fanned = hostTx.sent
            .filter { it.peerId == "g2" }
            .map { WtMessageCodec.decode(it.json) }
            .filterIsInstance<WtMessage.Pause>()
        assertEquals(1, fanned.size)
        assertTrue(hostTx.sent.none { it.peerId == "g1" && WtMessageCodec.decode(it.json) is WtMessage.Pause })
    }

    @Test
    fun `4 position messages last-wins by ts`() {
        val player = FakePlayerController()
        player.seekTo(0)
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()
        transport.sent.clear()

        transport.deliver(HOST_PEER_ID, pos(ts = 30, positionMs = 30_000))
        transport.deliver(HOST_PEER_ID, pos(ts = 10, positionMs = 10_000))
        transport.deliver(HOST_PEER_ID, pos(ts = 20, positionMs = 20_000))

        assertEquals(30_000, player.positionMs)
        assertEquals(30_000, player.lastSeekMs)
    }

    @Test
    fun `5 position within threshold no seek beyond seeks`() {
        val player = FakePlayerController()
        player.seekTo(10_000)
        val transport = FakeWtTransport()
        val guest = guestEngine(player, transport)
        guest.start()

        // Within 1500ms of 10_000 → no seek
        transport.deliver(HOST_PEER_ID, pos(ts = 1, positionMs = 10_500))
        assertEquals(10_000, player.positionMs)
        assertEquals(10_000, player.lastSeekMs)

        // Beyond threshold → seek
        transport.deliver(HOST_PEER_ID, pos(ts = 2, positionMs = 15_000))
        assertEquals(15_000, player.positionMs)
        assertEquals(15_000, player.lastSeekMs)
    }

    @Test
    fun `6 join wrong itemHash sends error containing item or hash`() {
        val transport = FakeWtTransport()
        val host = hostEngine(FakePlayerController(), transport)
        host.start()
        transport.connectPeer("g1")

        transport.deliver(
            "g1",
            WtMessageCodec.encode(
                WtMessage.Join(roomKey, "Priya", itemHash = "wrong-hash"),
            ),
        )

        val err = transport.sent
            .filter { it.peerId == "g1" }
            .map { WtMessageCodec.decode(it.json) }
            .filterIsInstance<WtMessage.Error>()
            .single()
        assertTrue(
            err.reason.contains("item", ignoreCase = true) ||
                err.reason.contains("hash", ignoreCase = true),
            err.reason,
        )
        assertEquals(1, host.participants().size)
    }

    @Test
    fun `7 tenth-plus participant rejected with room_full`() {
        var seq = 0
        val transport = FakeWtTransport()
        val host = SyncEngine(
            role = Role.HOST,
            roomKey = roomKey,
            itemHash = itemHash,
            localParticipant = Participant("host-1", "Alex", Role.HOST),
            player = FakePlayerController(),
            transport = transport,
            idGenerator = { "guest-${++seq}" },
        )
        host.start()

        repeat(SessionLimits.MAX_PARTICIPANTS - 1) { i ->
            val peer = "p$i"
            transport.connectPeer(peer)
            transport.deliver(
                peer,
                WtMessageCodec.encode(WtMessage.Join(roomKey, "G$i", itemHash)),
            )
        }
        assertEquals(SessionLimits.MAX_PARTICIPANTS, host.participants().size)

        transport.sent.clear()
        transport.connectPeer("overflow")
        transport.deliver(
            "overflow",
            WtMessageCodec.encode(WtMessage.Join(roomKey, "Nope", itemHash)),
        )

        val err = transport.sent
            .map { WtMessageCodec.decode(it.json) }
            .filterIsInstance<WtMessage.Error>()
            .single()
        assertEquals("room_full", err.reason)
        assertEquals(SessionLimits.MAX_PARTICIPANTS, host.participants().size)
    }

    @Test
    fun `8 peer disconnect removes participant without crash`() {
        var seq = 0
        val transport = FakeWtTransport()
        val host = SyncEngine(
            role = Role.HOST,
            roomKey = roomKey,
            itemHash = itemHash,
            localParticipant = Participant("host-1", "Alex", Role.HOST),
            player = FakePlayerController(),
            transport = transport,
            idGenerator = { "guest-${++seq}" },
        )
        host.start()
        transport.connectPeer("g1")
        transport.connectPeer("g2")
        transport.deliver(
            "g1",
            WtMessageCodec.encode(WtMessage.Join(roomKey, "Priya", itemHash)),
        )
        assertEquals(2, host.participants().size)

        transport.disconnectPeer("g1")

        assertEquals(1, host.participants().size)
        assertEquals("host-1", host.participants().single().id)
        val left = transport.sent
            .filter { it.peerId == "g2" }
            .map { WtMessageCodec.decode(it.json) }
            .filterIsInstance<WtMessage.ParticipantEvent>()
            .filter { it.op == WtMessage.ParticipantEvent.Op.LEFT }
        assertEquals(1, left.size)
    }

    private fun hostEngine(
        player: FakePlayerController,
        transport: FakeWtTransport,
    ) = SyncEngine(
        role = Role.HOST,
        roomKey = roomKey,
        itemHash = itemHash,
        localParticipant = Participant("host-1", "Alex", Role.HOST),
        player = player,
        transport = transport,
    )

    private fun guestEngine(
        player: FakePlayerController,
        transport: FakeWtTransport,
        id: String = "guest-temp",
    ) = SyncEngine(
        role = Role.GUEST,
        roomKey = roomKey,
        itemHash = itemHash,
        localParticipant = Participant(id, "Priya", Role.GUEST),
        player = player,
        transport = transport,
    )

    private fun pos(ts: Long, positionMs: Long, isPlaying: Boolean = true): String =
        WtMessageCodec.encode(WtMessage.Position(positionMs, isPlaying, ts))
}
