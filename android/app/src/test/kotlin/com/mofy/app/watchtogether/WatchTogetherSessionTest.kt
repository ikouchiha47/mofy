package com.mofy.app.watchtogether

import com.mofy.app.playback.FakePlayerController
import com.mofy.app.watchtogether.protocol.WtMessage
import com.mofy.app.watchtogether.protocol.WtMessageCodec
import com.mofy.app.watchtogether.transport.FakeWtTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchTogetherSessionTest {

    @Test
    fun `host forTest yields 6-char roomKey and deep link`() {
        val roomKey = RoomKey.generate()
        val session = WatchTogetherSession.forTest(
            roomKey = roomKey,
            role = Role.HOST,
            itemHash = "hash1",
            localParticipant = Participant("host-1", "Alex", Role.HOST),
            player = FakePlayerController(),
            transport = FakeWtTransport(),
            signalingUrl = "ws://127.0.0.1:9/wt/$roomKey",
        )
        assertEquals(6, session.roomKey.length)
        assertTrue(RoomKey.isValid(session.roomKey))
        assertNotNull(session.deepLink)
        assertTrue(session.deepLink.startsWith("mofy://wt/"))
        assertTrue(session.deepLink.contains(roomKey))
        session.end()
    }

    @Test
    fun `guest wrong itemHash emits error event not crash`() = runBlocking {
        val transport = FakeWtTransport()
        val session = WatchTogetherSession.forTest(
            roomKey = "7FK9Q2",
            role = Role.GUEST,
            itemHash = "guest-hash",
            localParticipant = Participant("guest-temp", "Priya", Role.GUEST),
            player = FakePlayerController(),
            transport = transport,
        )

        transport.deliver(
            "host",
            WtMessageCodec.encode(WtMessage.Error("item_hash_mismatch")),
        )

        val event = withTimeout(2_000) {
            session.events.first { it is WatchTogetherSession.WtEvent.Error }
        } as WatchTogetherSession.WtEvent.Error
        assertTrue(
            event.reason.contains("item", ignoreCase = true) ||
                event.reason.contains("hash", ignoreCase = true),
            event.reason,
        )
        session.end()
    }

    @Test
    fun `end closes cleanly and does not release player`() {
        val player = FakePlayerController()
        val transport = FakeWtTransport()
        val session = WatchTogetherSession.forTest(
            roomKey = "7FK9Q2",
            role = Role.HOST,
            itemHash = "h",
            localParticipant = Participant("host-1", "Alex", Role.HOST),
            player = player,
            transport = transport,
        )
        session.end()
        assertTrue(!player.isReleased)
        // transport closed — further send throws
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            runBlocking { transport.send("x", "{}") }
        }
    }
}
