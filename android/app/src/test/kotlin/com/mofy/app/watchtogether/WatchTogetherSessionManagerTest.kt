package com.mofy.app.watchtogether

import com.mofy.app.data.library.LibraryItem
import com.mofy.app.playback.FakePlayerController
import com.mofy.app.watchtogether.transport.FakeWtTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [WatchTogetherSessionManager] holds the list of sessions this device
 * currently participates in - a process-wide singleton (ADR 0011), not a
 * per-test instance, so every test resets it via [WatchTogetherSessionManager.clearAll]
 * to avoid cross-test pollution. Sessions here are built with
 * [WatchTogetherSession.forTest] (no real signaling/network), same as
 * WatchTogetherSessionTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherSessionManagerTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        WatchTogetherSessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        WatchTogetherSessionManager.clearAll()
        Dispatchers.resetMain()
    }

    private fun fakeSession(roomKey: String): WatchTogetherSession =
        WatchTogetherSession.forTest(
            roomKey = roomKey,
            role = Role.HOST,
            itemHash = "hash-$roomKey",
            localParticipant = Participant("host-1", "Alex", Role.HOST),
            player = FakePlayerController(),
            transport = FakeWtTransport(),
        )

    private fun fakeItem(id: String) = LibraryItem(
        id = id,
        tmdbId = null,
        mediaType = "movie",
        title = id,
        originalTitle = null,
        romanizedOriginalTitle = null,
        overview = "",
        posterPath = null,
        localPosterUri = null,
        posterSource = "NONE",
        year = null,
        genreIds = "",
        genresManual = null,
        voteAverage = 0.0,
        runtime = null,
        tagline = null,
        source = "MANUAL",
        addedAtEpochMillis = 0L,
        detailSyncedAtEpochMillis = null,
        feedback = null,
    )

    @Test
    fun `add appends a new session`() {
        val session = fakeSession("AAAAAA")

        WatchTogetherSessionManager.add(session, fakeItem("item-1"))

        assertEquals(1, WatchTogetherSessionManager.sessions.value.size)
        assertSame(session, WatchTogetherSessionManager.sessionFor("AAAAAA"))
        assertEquals("item-1", WatchTogetherSessionManager.itemFor("AAAAAA")?.id)
    }

    @Test
    fun `add with an existing roomKey replaces that entry instead of appending`() {
        val original = fakeSession("BBBBBB")
        val replacement = fakeSession("BBBBBB")
        WatchTogetherSessionManager.add(original, fakeItem("item-1"))

        WatchTogetherSessionManager.add(replacement, fakeItem("item-2"))

        assertEquals(1, WatchTogetherSessionManager.sessions.value.size)
        assertSame(replacement, WatchTogetherSessionManager.sessionFor("BBBBBB"))
        assertEquals("item-2", WatchTogetherSessionManager.itemFor("BBBBBB")?.id)
    }

    @Test
    fun `add beyond the concurrent session cap is rejected`() {
        repeat(SessionLimits.MAX_CONCURRENT_SESSIONS) { i ->
            WatchTogetherSessionManager.add(fakeSession("ROOM$i"), null)
        }
        assertEquals(SessionLimits.MAX_CONCURRENT_SESSIONS, WatchTogetherSessionManager.sessions.value.size)
        assertTrue(!WatchTogetherSessionManager.canAddMore)

        val overflow = fakeSession("OVERFLW")
        WatchTogetherSessionManager.add(overflow, null)

        assertEquals(SessionLimits.MAX_CONCURRENT_SESSIONS, WatchTogetherSessionManager.sessions.value.size)
        assertNull(WatchTogetherSessionManager.sessionFor("OVERFLW"))
    }

    @Test
    fun `remove ends the session's transport and drops it from the list`() {
        val transport = FakeWtTransport()
        val session = WatchTogetherSession.forTest(
            roomKey = "CCCCCC",
            role = Role.HOST,
            itemHash = "hash-CCCCCC",
            localParticipant = Participant("host-1", "Alex", Role.HOST),
            player = FakePlayerController(),
            transport = transport,
        )
        WatchTogetherSessionManager.add(session, null)

        WatchTogetherSessionManager.remove("CCCCCC")

        assertTrue(WatchTogetherSessionManager.sessions.value.isEmpty())
        assertNull(WatchTogetherSessionManager.sessionFor("CCCCCC"))
        // end() closes the transport — same assertion WatchTogetherSessionTest
        // uses to confirm teardown actually ran, not just left the list.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { transport.send("x", "{}") }
        }
    }

    @Test
    fun `remove on an unknown roomKey is a no-op`() {
        WatchTogetherSessionManager.add(fakeSession("DDDDDD"), null)

        WatchTogetherSessionManager.remove("NOPE00")

        assertEquals(1, WatchTogetherSessionManager.sessions.value.size)
    }

    @Test
    fun `removeLast targets the most recently added session`() {
        WatchTogetherSessionManager.add(fakeSession("FIRST1"), null)
        WatchTogetherSessionManager.add(fakeSession("SECOND"), null)

        WatchTogetherSessionManager.removeLast()

        assertEquals(1, WatchTogetherSessionManager.sessions.value.size)
        assertEquals("FIRST1", WatchTogetherSessionManager.sessions.value.single().session.roomKey)
        assertNull(WatchTogetherSessionManager.sessionFor("SECOND"))
    }

    @Test
    fun `clearAll ends every session and empties the list`() {
        WatchTogetherSessionManager.add(fakeSession("EEEEE1"), null)
        WatchTogetherSessionManager.add(fakeSession("EEEEE2"), null)

        WatchTogetherSessionManager.clearAll()

        assertTrue(WatchTogetherSessionManager.sessions.value.isEmpty())
    }
}
