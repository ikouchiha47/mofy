package com.mofy.app.ui.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mofy.app.playback.PlayerController
import com.mofy.app.playback.VlcPlayerController
import com.mofy.app.watchtogether.WatchTogetherSession
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

private const val SEEK_NUDGE_MS = 10_000L
private const val POSITION_POLL_MS = 500L
private const val EVENT_TOAST_MS = 2_500L

/**
 * Watch Together in-app player (mockup 4a-4d). Owns the [VlcPlayerController]
 * instance's lifecycle - created/released here, not by [WatchTogetherSession]
 * (its facade explicitly does not own the player). Transport controls go
 * through [createSession]'s session so remote peers see them (ADR 0006:
 * anyone may play/pause/seek); subtitle/audio track selection is local-only
 * and calls the player directly.
 */
@Composable
fun PlayerScreen(
    contentPadding: PaddingValues,
    mediaUri: String,
    itemTitle: String,
    createSession: (PlayerController) -> WatchTogetherSession,
    onBack: () -> Unit,
    onInvite: () -> Unit,
) {
    val context = LocalContext.current
    val videoLayout = remember { VLCVideoLayout(context) }
    var player by remember { mutableStateOf<VlcPlayerController?>(null) }
    var session by remember { mutableStateOf<WatchTogetherSession?>(null) }

    DisposableEffect(mediaUri) {
        val newPlayer = VlcPlayerController(context, mediaUri)
        newPlayer.attachViews(videoLayout)
        val newSession = createSession(newPlayer)
        player = newPlayer
        session = newSession
        onDispose {
            newSession.end()
            newPlayer.detachViews()
            newPlayer.release()
        }
    }

    val currentPlayer = player
    val currentSession = session ?: run {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val state by currentSession.state.collectAsState()

    var uiPositionMs by remember { mutableStateOf(0L) }
    var uiIsPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(currentPlayer) {
        while (true) {
            uiPositionMs = currentPlayer?.positionMs ?: 0L
            uiIsPlaying = currentPlayer?.isPlaying ?: false
            delay(POSITION_POLL_MS)
        }
    }

    var eventText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSession) {
        currentSession.events.collect { event ->
            eventText = when (event) {
                is WatchTogetherSession.WtEvent.ParticipantJoined -> "${event.participant.displayName} joined"
                is WatchTogetherSession.WtEvent.ParticipantLeft -> "A participant left"
                is WatchTogetherSession.WtEvent.Error -> event.reason
                WatchTogetherSession.WtEvent.Ended -> {
                    onBack()
                    null
                }
                WatchTogetherSession.WtEvent.Joined -> null
            }
        }
    }
    LaunchedEffect(eventText) {
        if (eventText != null) {
            delay(EVENT_TOAST_MS)
            eventText = null
        }
    }

    var subtitleOn by remember { mutableStateOf(false) }
    var altAudio by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { videoLayout }, modifier = Modifier.fillMaxSize())

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(12.dp))
            WatchingWithPill(
                localParticipantId = state.localParticipantId,
                participants = state.participants,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            eventText?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .padding(bottom = 12.dp),
                )
            }

            val durationMs = currentPlayer?.durationMs?.takeIf { it > 0L } ?: 1L
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(formatMs(uiPositionMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
                Slider(
                    value = (uiPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { fraction -> uiPositionMs = (fraction * durationMs).toLong() },
                    onValueChangeFinished = { currentSession.localSeek(uiPositionMs) },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { currentSession.localSeek((uiPositionMs - SEEK_NUDGE_MS).coerceAtLeast(0L)) }) {
                        Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White)
                    }
                    IconButton(
                        onClick = { if (uiIsPlaying) currentSession.localPause() else currentSession.localPlay() },
                    ) {
                        Icon(
                            if (uiIsPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (uiIsPlaying) "Pause" else "Play",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { currentSession.localSeek(uiPositionMs + SEEK_NUDGE_MS) }) {
                        Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(onClick = onInvite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.People, contentDescription = "Invite", tint = Color.White)
                }
            }

            // No track-name enumeration on PlayerController - libVLC's
            // MediaPlayer.getSpuTracks()/getAudioTracks() aren't exposed
            // through the abstraction, so these are generic on/off toggles
            // rather than the mockup's named tracks (e.g. "Français").
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Subs", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.width(8.dp))
                TrackChip(label = "Off", selected = !subtitleOn) {
                    subtitleOn = false
                    currentPlayer?.setSubtitleTrack(null)
                }
                Spacer(modifier = Modifier.width(6.dp))
                TrackChip(label = "On", selected = subtitleOn) {
                    subtitleOn = true
                    currentPlayer?.setSubtitleTrack(0)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("Audio", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.width(8.dp))
                TrackChip(label = "Default", selected = !altAudio) {
                    altAudio = false
                    currentPlayer?.setAudioTrack(null)
                }
                Spacer(modifier = Modifier.width(6.dp))
                TrackChip(label = "Alt", selected = altAudio) {
                    altAudio = true
                    currentPlayer?.setAudioTrack(1)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text("your device only", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun WatchingWithPill(
    localParticipantId: String,
    participants: List<com.mofy.app.watchtogether.Participant>,
) {
    val others = participants.filter { it.id != localParticipantId }
    val label = when {
        others.isEmpty() -> "Watching alone"
        others.size == 1 -> "Watching with ${others[0].displayName}"
        else -> "Watching with ${others[0].displayName} +${others.size - 1}"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.tertiary),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun TrackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun formatMs(ms: Long): String {
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
