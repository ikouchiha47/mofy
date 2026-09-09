package com.mofy.app.playback.youtube

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real-network smoke test, same rationale as YoutubeStreamResolverTest - a mock can't catch YouTube changing something underneath the extractor. */
class YoutubeSearchClientTest {

    @Test
    fun `searches YouTube and returns video id, title, duration, thumbnail`() {
        val results = YoutubeSearchClient.search("Big Buck Bunny 4K")

        assertTrue(results.isNotEmpty(), "expected at least one search result")
        val first = results.first()
        assertTrue(first.videoId.isNotBlank() && !first.videoId.startsWith("http"), "videoId should be a bare id, was ${first.videoId}")
        assertTrue(first.title.isNotBlank(), "title should not be blank")
        assertTrue(first.durationSeconds > 0, "duration should be positive, was ${first.durationSeconds}")
        assertTrue(first.thumbnailUrl?.startsWith("http") == true, "thumbnail URL should be http(s), was ${first.thumbnailUrl}")

        println("First result: ${first.videoId} ${first.title!!} (${first.durationSeconds}s) ${first.thumbnailUrl}")
    }
}
