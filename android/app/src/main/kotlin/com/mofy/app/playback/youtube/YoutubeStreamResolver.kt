package com.mofy.app.playback.youtube

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * A YouTube stream resolved to directly-playable URL(s).
 *
 * [audioStreamUrl] is set when a higher-quality video-only DASH stream was
 * available and paired with a separate audio-only one - libVLC plays
 * [streamUrl] as the primary media and [audioStreamUrl] as an
 * [org.videolan.libvlc.interfaces.IMedia.Slave.Type.Audio] slave (same
 * mechanism [com.mofy.app.playback.VlcPlayerController] already uses for
 * subtitle slaves), rather than through any DASH manifest. Null when only
 * the lower-quality progressive (video+audio muxed) stream was found -
 * nothing to attach as a slave in that case.
 */
data class YoutubeResolvedStream(
    val title: String,
    val durationSeconds: Long,
    val streamUrl: String,
    val resolution: String,
    val audioStreamUrl: String? = null,
)

/**
 * One `fetchPage()` worth of NewPipeExtractor data for a video - lets a
 * caller inspect [availableResolutions] and then [resolve] a chosen one
 * without a second network round trip; the extractor already has
 * everything needed after the single fetch in [YoutubeStreamResolver.fetch].
 */
class YoutubeStreamHandle internal constructor(private val extractor: StreamExtractor) {
    val title: String get() = extractor.name
    val durationSeconds: Long get() = extractor.length

    /** Video-only DASH resolutions, best first (e.g. "1080p", "720p", ...) - empty if this video only has progressive streams. */
    val availableResolutions: List<String> by lazy {
        extractor.videoOnlyStreams
            .filterIsInstance<VideoStream>()
            .filter { it.isUrl }
            .distinctBy { it.resolution }
            .sortedByDescending { it.itagItem?.bitrate ?: 0 }
            .map { it.resolution }
    }

    /**
     * [resolution] should be one of [availableResolutions] - if null or not
     * actually available, falls back to the highest video-only resolution;
     * the returned [YoutubeResolvedStream.resolution] always reports what
     * was actually picked, so callers can tell when a request fell back.
     */
    fun resolve(resolution: String? = null): YoutubeResolvedStream {
        val videoOnlyCandidates = extractor.videoOnlyStreams
            .filterIsInstance<VideoStream>()
            .filter { it.isUrl }
        val chosenVideoOnly = resolution?.let { r -> videoOnlyCandidates.firstOrNull { it.resolution == r } }
            ?: videoOnlyCandidates.maxByOrNull { it.itagItem?.bitrate ?: 0 }
        val bestAudio = extractor.audioStreams
            .filter { it.isUrl }
            .maxByOrNull { it.averageBitrate }

        if (chosenVideoOnly != null && bestAudio != null) {
            return YoutubeResolvedStream(
                title = extractor.name,
                durationSeconds = extractor.length,
                streamUrl = chosenVideoOnly.content,
                resolution = chosenVideoOnly.resolution,
                audioStreamUrl = bestAudio.content,
            )
        }

        // No video-only/audio-only DASH pair for this title - fall back to
        // progressive (video+audio muxed, capped at 360p by YouTube).
        val progressive = extractor.videoStreams
            .filterIsInstance<VideoStream>()
            .filterNot { it.isVideoOnly }
            .filter { it.isUrl }
            .maxByOrNull { it.itagItem?.bitrate ?: 0 }
            ?: error("No playable stream (progressive or DASH) found for ${extractor.name}")

        return YoutubeResolvedStream(
            title = extractor.name,
            durationSeconds = extractor.length,
            streamUrl = progressive.content,
            resolution = progressive.resolution,
        )
    }
}

/**
 * Fetches a YouTube video ID/URL's stream data via NewPipeExtractor, for
 * feeding into [com.mofy.app.playback.VlcPlayerController] the same way a
 * local file:// URI is today - libVLC just plays whatever URI it's given,
 * http(s) included.
 *
 * A resolved googlevideo.com URL is commonly IP-locked and short-lived, so
 * each device must fetch independently rather than sharing a resolved URL
 * over the Watch Together signaling channel - only the video ID should ever
 * cross that channel.
 */
object YoutubeStreamResolver {
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

    /** [videoIdOrUrl] may be a bare 11-char YouTube ID or a full watch URL. */
    fun fetch(videoIdOrUrl: String): YoutubeStreamHandle {
        ensureInit()
        val url = if (videoIdOrUrl.startsWith("http")) videoIdOrUrl else "https://www.youtube.com/watch?v=$videoIdOrUrl"
        val extractor = NewPipe.getServiceByUrl(url).getStreamExtractor(url)
        extractor.fetchPage()
        return YoutubeStreamHandle(extractor)
    }
}
