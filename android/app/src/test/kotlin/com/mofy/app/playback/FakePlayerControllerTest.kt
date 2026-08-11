package com.mofy.app.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakePlayerControllerTest {

    @Test
    fun `seekTo updates positionMs and records lastSeek`() {
        val player = FakePlayerController()

        player.seekTo(1234)

        assertEquals(1234L, player.positionMs)
        assertEquals(1234L, player.lastSeekMs)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `play and pause flip isPlaying`() {
        val player = FakePlayerController()

        player.play()
        assertTrue(player.isPlaying)

        player.pause()
        assertFalse(player.isPlaying)
    }

    @Test
    fun `track setters record the selected index independently`() {
        val player = FakePlayerController()

        player.setSubtitleTrack(1)
        player.setAudioTrack(2)

        assertEquals(1, player.subtitleTrackImpl)
        assertEquals(2, player.audioTrackImpl)
    }

    @Test
    fun `null track index clears the selection`() {
        val player = FakePlayerController()
        player.setSubtitleTrack(0)

        player.setSubtitleTrack(null)

        assertNull(player.subtitleTrackImpl)
    }

    @Test
    fun `release marks the player released`() {
        val player = FakePlayerController()

        player.release()

        assertTrue(player.isReleased)
    }
}