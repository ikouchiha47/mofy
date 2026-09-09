package com.mofy.app.playback.youtube

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** One YouTube search result - thumbnail/title/duration only, no channel (not shown/stored anywhere downstream). */
data class YoutubeSearchResult(
    val videoId: String,
    val title: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
)

/**
 * Wraps NewPipeExtractor's YouTube search (not a WebView, not scraping the
 * search results page) for Discover's YouTube source and the "attach a
 * YouTube link" flow on an existing library item.
 */
object YoutubeSearchClient {
    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(OkHttpDownloader.instance)
            initialized = true
        }
    }

    fun search(query: String): List<YoutubeSearchResult> {
        ensureInit()
        val service = NewPipe.getService("YouTube")
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()

        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                YoutubeSearchResult(
                    // item.url is a full watch URL - YoutubeStreamResolver/
                    // YoutubeSearchClient's own callers only ever need the
                    // bare video ID (that's what gets stored in movieUri).
                    videoId = videoIdFromUrl(item.url) ?: item.url,
                    title = item.name,
                    durationSeconds = item.duration,
                    thumbnailUrl = item.thumbnails.maxByOrNull { it.height }?.url,
                )
            }
    }

    private fun videoIdFromUrl(url: String): String? =
        Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1)
}
