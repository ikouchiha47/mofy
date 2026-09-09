package com.mofy.app.playback.youtube

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real-network smoke test - deliberately not mocked, since the actual risk
 * with NewPipeExtractor is YouTube changing something underneath it, which a
 * mock would never catch. Big Buck Bunny's official upload is used as the
 * target: a stable, unlikely-to-be-taken-down public video, not tied to any
 * account/region-specific availability.
 */
class YoutubeStreamResolverTest {

    @Test
    fun `lists available resolutions and resolves the best one to a video+audio stream pair`() {
        val handle = YoutubeStreamResolver.fetch("aqz-KE-bpKQ")

        assertTrue(handle.title.isNotBlank(), "title should not be blank")
        assertTrue(handle.durationSeconds > 0, "duration should be positive, was ${handle.durationSeconds}")
        assertTrue(handle.availableResolutions.isNotEmpty(), "expected at least one video-only resolution for this title")
        println("Available resolutions: ${handle.availableResolutions}")

        val result = handle.resolve()
        assertTrue(result.streamUrl.startsWith("http"), "stream URL should be http(s), was ${result.streamUrl}")
        assertTrue(result.resolution.isNotBlank(), "resolution should not be blank")
        assertTrue(result.audioStreamUrl != null, "expected a separate audio-only stream for the DASH path")
        assertTrue(result.audioStreamUrl!!.startsWith("http"), "audio stream URL should be http(s)")

        println("Resolved: ${result.title} (${result.resolution}, ${result.durationSeconds}s)")
        println("Video URL: ${result.streamUrl}")
        println("Audio URL: ${result.audioStreamUrl}")
    }

    @Test
    fun `resolving a specific resolution returns that resolution, not just the best`() {
        val handle = YoutubeStreamResolver.fetch("aqz-KE-bpKQ")
        val requested = handle.availableResolutions.last() // lowest available, to distinguish from "best"

        val result = handle.resolve(requested)

        assertTrue(result.resolution == requested, "expected $requested, got ${result.resolution}")
    }

    @Test
    fun `resolves from a full watch URL the same as from a bare video ID`() {
        val byId = YoutubeStreamResolver.fetch("aqz-KE-bpKQ")
        val byUrl = YoutubeStreamResolver.fetch("https://www.youtube.com/watch?v=aqz-KE-bpKQ")

        assertTrue(byId.title == byUrl.title, "same video should resolve to the same title either way")
    }
}
