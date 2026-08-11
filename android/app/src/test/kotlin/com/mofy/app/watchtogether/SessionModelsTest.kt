package com.mofy.app.watchtogether

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SessionModelsTest {

    @Test
    fun `generated keys are always valid`() {
        val random = Random(42L)
        repeat(200) {
            val key = RoomKey.generate(random)
            assertTrue(RoomKey.isValid(key), "unexpected key: $key")
            assertEquals(RoomKey.LENGTH, key.length)
        }
    }

    @Test
    fun `normalize strips separators and uppercases`() {
        assertEquals("7FK9Q2", RoomKey.normalize("7f k9 q2"))
        assertEquals("7FK9Q2", RoomKey.normalize("7f.k9.q2"))
    }

    @Test
    fun `normalize rejects letters outside alphabet and wrong length`() {
        assertFalse(RoomKey.isValid("7FK9Q"))
        assertFalse(RoomKey.isValid("7FKOQ2")) // contains O
        assertFalse(RoomKey.isValid("7FK90Q")) // contains 0
        assertFalse(RoomKey.isValid("7FK9Q2A")) // 7 alphabet chars is not a valid code
    }

    @Test
    fun `alphabet excludes ambiguous characters`() {
        val ambiguous = listOf('0', 'O', '1', 'I')
        ambiguous.forEach { c ->
            assertFalse(c in RoomKey.ALPHABET, "$c should not be in the alphabet")
        }
    }

    @Test
    fun `max participants constant is ten`() {
        assertEquals(10, SessionLimits.MAX_PARTICIPANTS)
    }
}