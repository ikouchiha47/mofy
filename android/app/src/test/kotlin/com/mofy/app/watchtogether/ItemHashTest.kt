package com.mofy.app.watchtogether

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItemHashTest {

    @Test
    fun `same tmdbId and mediaType produce equal hash regardless of library id`() {
        val a = ItemHash.of(tmdbId = 101, mediaType = "Movie", title = "Jack Reacher")
        val b = ItemHash.of(tmdbId = 101, mediaType = "movie", title = "totally different title")

        assertEquals(a, b)
    }

    @Test
    fun `tmdb identity wins over title`() {
        val a = ItemHash.of(tmdbId = 101, mediaType = "Movie", title = "Jack Reacher")
        val b = ItemHash.of(tmdbId = 202, mediaType = "Movie", title = "Jack Reacher")

        assertNotEquals(a, b)
    }

    @Test
    fun `different mediaType with same tmdbId differ`() {
        val movie = ItemHash.of(tmdbId = 101, mediaType = "Movie", title = "X")
        val tv = ItemHash.of(tmdbId = 101, mediaType = "TV", title = "X")

        assertNotEquals(movie, tv)
    }

    @Test
    fun `capitalization and punctuation normalize to same title hash`() {
        val a = ItemHash.of(tmdbId = null, mediaType = null, title = "Jack Reacher!")
        val b = ItemHash.of(tmdbId = null, mediaType = null, title = "jack   reacher")

        assertEquals(a, b)
    }

    @Test
    fun `genuinely different titles do not collide`() {
        val a = ItemHash.of(tmdbId = null, mediaType = null, title = "Jack Reacher")
        val b = ItemHash.of(tmdbId = null, mediaType = null, title = "Jack Reacher 2")

        assertNotEquals(a, b)
    }

    @Test
    fun `empty and symbol-only titles still hash deterministically`() {
        val empty = ItemHash.of(tmdbId = null, mediaType = null, title = "")
        val symbols = ItemHash.of(tmdbId = null, mediaType = null, title = "!!!   ")

        assertEquals(empty, symbols)
        assertTrue(empty.length == 16)
    }

    @Test
    fun `hash is fixed length hex`() {
        ItemHash.of(tmdbId = null, mediaType = null, title = "Some Obscure Documentary")
            .let {
                assertTrue(it.length == 16)
                assertTrue(it.all { c -> c in "0123456789abcdef" })
            }
    }
}