package com.mofy.app.playback.youtube

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolve state for a LibraryLink.movieUri that may or may not be a
 * "youtube:<videoId>?res=<resolution>" link - both playback entry points
 * (MainActivity's SOLO_PLAY route and the Watch Together PlayerScreen route)
 * need this same gate before constructing VlcPlayerController, since a
 * resolve is a real network round trip local files never needed.
 */
sealed interface YoutubeMediaState {
    /** movieUri isn't a youtube: link - caller should just use it directly, unchanged. */
    data object NotYoutube : YoutubeMediaState
    data object Loading : YoutubeMediaState
    data class Ready(val streamUrl: String, val audioStreamUrl: String?, val resolution: String) : YoutubeMediaState
    data class Error(val message: String) : YoutubeMediaState
}

/** Parses "youtube:<videoId>?res=<resolution>" - resolution is optional (falls back to best in YoutubeStreamHandle.resolve). */
private fun parseYoutubeUri(movieUri: String): Pair<String, String?> {
    val rest = movieUri.removePrefix("youtube:")
    val videoId = rest.substringBefore("?res=")
    val resolution = rest.substringAfter("?res=", "").takeIf { it.isNotEmpty() }
    return videoId to resolution
}

@Composable
fun rememberYoutubeResolvedMedia(movieUri: String): YoutubeMediaState {
    var state by remember(movieUri) {
        mutableStateOf<YoutubeMediaState>(
            if (movieUri.startsWith("youtube:")) YoutubeMediaState.Loading else YoutubeMediaState.NotYoutube,
        )
    }
    LaunchedEffect(movieUri) {
        if (!movieUri.startsWith("youtube:")) {
            state = YoutubeMediaState.NotYoutube
            return@LaunchedEffect
        }
        state = YoutubeMediaState.Loading
        state = try {
            val (videoId, resolution) = parseYoutubeUri(movieUri)
            val resolved = withContext(Dispatchers.IO) { YoutubeStreamResolver.fetch(videoId).resolve(resolution) }
            YoutubeMediaState.Ready(resolved.streamUrl, resolved.audioStreamUrl, resolved.resolution)
        } catch (e: Exception) {
            YoutubeMediaState.Error(e.message ?: "Failed to resolve YouTube stream")
        }
    }
    return state
}
