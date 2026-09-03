package com.mofy.app.ui.watchtogether

import android.app.Activity
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mofy.app.playback.VlcPlayerController
import com.mofy.app.ui.icons.AppIcons
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Thin custom seek track matching design/player-ui-mockup.html exactly - a
 * 3dp red fill on a translucent-white rail with a small round thumb, not
 * Material3's default Slider (thicker track, larger thumb, ripple/halo on
 * press) which didn't match the reference.
 */
@Composable
private fun SeekTrack(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    onFractionChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .height(24.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        if (widthPx > 0f) onFractionChange((change.position.x / widthPx).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onFractionChangeFinished() },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.25f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = with(LocalDensity.current) { ((widthPx * fraction) - 6.dp.toPx()).coerceAtLeast(0f).toDp() }),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private const val SEEK_NUDGE_MS = 10_000L
private const val POSITION_POLL_MS = 500L
private const val GESTURE_ZONE_WIDTH_FRACTION = 0.3f
private const val DRAG_TO_FULL_RANGE_PX = 600f

/**
 * In-app VLC playback for a single local/content file, no Watch Together
 * session - PlayerScreen's createSession param requires a full
 * WatchTogetherSession (host/guest, signaling), which solo playback has no
 * use for.
 *
 * Layout is YouTube-style per the user's explicit spec (design/player-ui-
 * mockup.html is the reference): a single centered play/pause button with
 * rewind/forward at its sides - not a row of equal-sized icons - plus
 * invisible vertical drag zones on the left/right edges for brightness and
 * volume that don't interfere with the horizontal seek slider running along
 * the bottom.
 */
@Composable
fun SoloPlayerScreen(
    contentPadding: PaddingValues,
    mediaUri: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val videoLayout = remember { VLCVideoLayout(context) }
    var player by remember { mutableStateOf<VlcPlayerController?>(null) }

    DisposableEffect(mediaUri) {
        val newPlayer = VlcPlayerController(context, mediaUri)
        newPlayer.attachViews(videoLayout)
        newPlayer.play()
        player = newPlayer
        onDispose {
            newPlayer.detachViews()
            newPlayer.release()
        }
    }

    val currentPlayer = player ?: run {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    var uiPositionMs by remember { mutableStateOf(0L) }
    var uiIsPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(currentPlayer) {
        while (true) {
            uiPositionMs = currentPlayer.positionMs
            uiIsPlaying = currentPlayer.isPlaying
            delay(POSITION_POLL_MS)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { videoLayout }, modifier = Modifier.fillMaxSize())

        // Left edge - invisible vertical drag for screen brightness.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(GESTURE_ZONE_WIDTH_FRACTION)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        val window = activity?.window ?: return@detectVerticalDragGestures
                        val params = window.attributes
                        val current = params.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                        params.screenBrightness = (current - dragAmount / DRAG_TO_FULL_RANGE_PX).coerceIn(0.01f, 1f)
                        window.attributes = params
                    }
                },
        )

        // Right edge - invisible vertical drag for media volume.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(GESTURE_ZONE_WIDTH_FRACTION)
                .pointerInput(Unit) {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    detectVerticalDragGestures { _, dragAmount ->
                        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val delta = (-dragAmount / DRAG_TO_FULL_RANGE_PX * maxVolume)
                        val next = (current + delta).toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                    }
                },
        )

        Box(
            modifier = Modifier
                .padding(contentPadding)
                .padding(16.dp)
                .size(36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        // Center controls - single play/pause button with rewind/forward
        // at its sides, YouTube-style (not a row of equal icons).
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { currentPlayer.seekTo((uiPositionMs - SEEK_NUDGE_MS).coerceAtLeast(0L)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Replay10, contentDescription = "Back 10 seconds", tint = Color.White.copy(alpha = 0.85f))
            }
            Box(modifier = Modifier.width(48.dp))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { if (uiIsPlaying) currentPlayer.pause() else currentPlayer.play() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (uiIsPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    contentDescription = if (uiIsPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Box(modifier = Modifier.width(48.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { currentPlayer.seekTo(uiPositionMs + SEEK_NUDGE_MS) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White.copy(alpha = 0.85f))
            }
        }

        // Bottom seek bar - padded past contentPadding to clear Android's
        // native gesture/nav bar (the mockup's reserved bottom inset).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val durationMs = currentPlayer.durationMs.takeIf { it > 0L } ?: 1L
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(formatMs(uiPositionMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
                SeekTrack(
                    fraction = (uiPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                    onFractionChange = { fraction -> uiPositionMs = (fraction * durationMs).toLong() },
                    onFractionChangeFinished = { currentPlayer.seekTo(uiPositionMs) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}
