package com.mofy.app.watchtogether

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RoomCodeTest {

    @Test
    fun `formatForDisplay groups exactly 6 chars`() {
        assertEquals("7F · K9 · Q2", RoomCode.formatForDisplay("7FK9Q2"))
    }

    @Test
    fun `formatForDisplay passthrough for non-6-char input`() {
        assertEquals("7FK9", RoomCode.formatForDisplay("7FK9"))
        assertEquals("", RoomCode.formatForDisplay(""))
    }

    @Test
    fun `parseUserInput accepts raw and grouped forms`() {
        assertEquals("7FK9Q2", RoomCode.parseUserInput("7FK9Q2"))
        assertEquals("7FK9Q2", RoomCode.parseUserInput("7F · K9 · Q2"))
        assertEquals("7FK9Q2", RoomCode.parseUserInput(" 7f-k9.q2 "))
    }

    @Test
    fun `parseUserInput rejects invalid input`() {
        assertNull(RoomCode.parseUserInput(""))
        assertNull(RoomCode.parseUserInput("123456")) // 1/2 not in alphabet
        assertNull(RoomCode.parseUserInput("TOOSHORT"))
        assertNull(RoomCode.parseUserInput(""))
    }

    @Test
    fun `toDeepLink without sig`() {
        assertEquals("mofy://wt/7FK9Q2", RoomCode.toDeepLink("7FK9Q2"))
    }

    @Test
    fun `toDeepLink with sig`() {
        assertEquals(
            "mofy://wt/7FK9Q2?sig=ws://192.168.1.10:9999/",
            RoomCode.toDeepLink("7FK9Q2", signalingUrl = "ws://192.168.1.10:9999/"),
        )
    }

    @Test
    fun `parseDeepLink without sig round-trips`() {
        val parsed = RoomCode.parseDeepLink("mofy://wt/7FK9Q2")!!
        assertEquals("7FK9Q2", parsed.roomKey)
        assertNull(parsed.signalingUrl)
    }

    @Test
    fun `parseDeepLink with sig round-trips`() {
        val parsed = RoomCode.parseDeepLink("mofy://wt/7FK9Q2?sig=ws://192.168.1.10:9999/")!!
        assertEquals("7FK9Q2", parsed.roomKey)
        assertEquals("ws://192.168.1.10:9999/", parsed.signalingUrl)
    }

    @Test
    fun `parseDeepLink lowercases room key to canonical form`() {
        val parsed = RoomCode.parseDeepLink("mofy://wt/7fk9q2")!!
        assertEquals("7FK9Q2", parsed.roomKey)
    }

    @Test
    fun `parseDeepLink is derived from toDeepLink`() {
        val roomKey = RoomKey.generate()
        assertEquals(roomKey, RoomCode.parseDeepLink(RoomCode.toDeepLink(roomKey))!!.roomKey)
    }

    @Test
    fun `parseDeepLink rejects non-mofy or non-wt uris`() {
        assertNull(RoomCode.parseDeepLink("https://example.com/wt/7FK9Q2"))
        assertNull(RoomCode.parseDeepLink("mofy://other/7FK9Q2"))
        assertNull(RoomCode.parseDeepLink("mofy://wt/short"))
    }
}