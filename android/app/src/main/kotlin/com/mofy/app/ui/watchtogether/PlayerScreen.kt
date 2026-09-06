package com.mofy.app.ui.watchtogether

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.mofy.app.playback.PlayerController
import com.mofy.app.watchtogether.WatchTogetherSession

/**
 * Watch Together's player is now just [SoloPlayerScreen] with a
 * [createSession]/[onInvite] hookup - the two players share one UI (gesture
 * zones, seek track, subtitle picker, center transport) per the "the two
 * player's base UI would match" direction. What differs live-session-only:
 * a people icon with a participant-count badge (replaces the old
 * always-visible "Watching with Priya +1" pill) and a transient top toast
 * for join/leave/error events - both built into [SoloPlayerScreen] behind
 * its optional `createSession` param.
 */
@Composable
fun PlayerScreen(
    contentPadding: PaddingValues,
    mediaUri: String,
    itemTitle: String,
    createSession: (PlayerController) -> WatchTogetherSession,
    onBack: () -> Unit,
    onInvite: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
) {
    SoloPlayerScreen(
        contentPadding = contentPadding,
        mediaUri = mediaUri,
        createSession = createSession,
        onBack = onBack,
        onInvite = onInvite,
        onProgress = onProgress,
    )
}

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
