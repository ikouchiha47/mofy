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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mofy.app.playback.VideoScale
import com.mofy.app.playback.VlcPlayerController
import com.mofy.app.ui.icons.AppIcons
import com.mofy.app.watchtogether.WatchTogetherSession
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
private const val PROGRESS_SAVE_MS = 5_000L
private const val EVENT_TOAST_MS = 2_500L
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
    subtitleUri: String? = null,
    subtitle2Uri: String? = null,
    initialPositionMs: Long = 0L,
    createSession: ((com.mofy.app.playback.PlayerController) -> WatchTogetherSession)? = null,
    onBack: () -> Unit,
    onInvite: (() -> Unit)? = null,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val videoLayout = remember { VLCVideoLayout(context) }
    var player by remember { mutableStateOf<VlcPlayerController?>(null) }
    var session by remember { mutableStateOf<WatchTogetherSession?>(null) }
    var subtitleMenuOpen by remember { mutableStateOf(false) }
    var videoScale by remember { mutableStateOf(VideoScale.FIT) }
    var aspectMenuOpen by remember { mutableStateOf(false) }
    var peopleMenuOpen by remember { mutableStateOf(false) }
    var currentSubtitleTrack by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(mediaUri, subtitleUri, subtitle2Uri) {
        // mediaUri starts as "" before the DB-backed link flow resolves,
        // then changes to the real URI moments later - firing this effect
        // twice per real entry (blank, then real). Each firing constructed
        // a throwaway VlcPlayerController + Surface that got torn down
        // almost immediately, which is what produced the "video output
        // creation failed" flood and the flash-of-black/restart-from-0 on
        // every entry. Skip construction entirely until there's a real URI.
        if (mediaUri.isBlank()) return@DisposableEffect onDispose {}

        val newPlayer = VlcPlayerController(context, mediaUri, subtitleUri, subtitle2Uri, initialPositionMs)
        newPlayer.attachViews(videoLayout)
        val newSession = createSession?.invoke(newPlayer)
        if (newSession == null) newPlayer.play()
        player = newPlayer
        session = newSession
        onDispose {
            // Session lifecycle is NOT owned here - ending it on mere
            // navigation-away (Back/minimize) would kill the connection
            // for every connected guest just from backgrounding. Confirmed
            // bug on a real device: the desktop guest's WebSocket closed
            // instantly on any dispose of this screen, not just an
            // intentional Leave. Teardown is explicit only, via
            // WatchTogetherSessionManager/ViewModel's remove()/clearAll()
            // (Leave/Cancel actions) - only local player resources are
            // released here, so the session can be rebound via
            // rebindPlayer() if this screen is re-entered later.
            // Forbidden for a Watch Together session (docs/tasks/watch-
            // together-state-and-ux.md §3.E): this reads the about-to-be-
            // released VLC player, not the room clock - flushing it into
            // the bookmark on every Back would smear a paused-elsewhere or
            // headless-drifted position into Continue Watching. FGS is the
            // one place that flushes a room's position on Leave/HostLost.
            if (newSession == null) {
                runCatching {
                    if (newPlayer.durationMs > 0) onProgress(newPlayer.positionMs, newPlayer.durationMs)
                }
            }
            newPlayer.detachViews()
            newPlayer.release()
        }
    }

    val currentPlayer = player ?: run {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val currentSession = session

    // The window's Surface (and the SurfaceView inside videoLayout) gets
    // destroyed and recreated by the OS on any visibility loss - not just
    // backgrounding, but the screen simply timing out and turning off from
    // inactivity (confirmed via PowerManagerService/DreamManagerService
    // logs: "Going to sleep due to timeout" / "Waking up ... WAKE_REASON_
    // TAP"). VLC's video output stays bound to the old, now-dead Surface
    // unless told to rebind, producing a continuous "video output creation
    // failed" flood - audio keeps playing (different pipeline) while video
    // stays blank. Detach before the Surface dies, reattach once a fresh
    // one exists.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> currentPlayer.detachViews()
                Lifecycle.Event.ON_RESUME -> currentPlayer.attachViews(videoLayout)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var uiPositionMs by remember { mutableStateOf(0L) }
    var uiIsPlaying by remember { mutableStateOf(false) }
    // While actively dragging the seek track, incoming store/poll updates
    // must not clobber the in-progress local value (docs/tasks/watch-
    // together-state-and-ux.md: "Slider value while dragging ... must not
    // [be overwritten]" - local until scrub-end).
    var isDraggingSeek by remember { mutableStateOf(false) }

    if (currentSession != null) {
        // Watch Together: mirror the room's own store directly instead of
        // polling the local player - correct even when this device's
        // player is released/headless or lagging, and it's what makes a
        // remote participant's seek/pause actually show up here (§8 step 3).
        val liveState by currentSession.state.collectAsState()
        if (!isDraggingSeek) uiPositionMs = liveState.positionMs
        uiIsPlaying = liveState.isPlaying
    } else {
        LaunchedEffect(currentPlayer) {
            while (true) {
                // The underlying native VLC object can be released out from
                // under this loop by something other than this composable's
                // own onDispose (e.g. a Watch Together teardown firing while
                // this screen is still composed but minimized/backgrounded) -
                // confirmed crash on a real device: IllegalStateException
                // "can't get VLCObject instance" from getTime() on an
                // already-released player. Not recoverable mid-loop; just stop
                // polling instead of crashing the app.
                val positionMs = runCatching { currentPlayer.positionMs }.getOrNull()
                if (positionMs == null) break
                if (!isDraggingSeek) uiPositionMs = positionMs
                uiIsPlaying = runCatching { currentPlayer.isPlaying }.getOrElse { false }
                delay(POSITION_POLL_MS)
            }
        }
    }

    // Mirrors VLC-Android's own VideoPlayerActivity: keepScreenOn is toggled
    // with play/pause state on the video surface itself
    // (mSurfaceView.setKeepScreenOn(true) on play, false on pause/stop), not
    // held for the whole time the screen is open - so the screen still times
    // out normally while paused instead of staying lit for no reason.
    LaunchedEffect(uiIsPlaying) {
        videoLayout.keepScreenOn = uiIsPlaying
    }

    // Solo playback (and a Watch Together session demoted to solo, see
    // WtEvent.HostLost below) never persisted progress at all before this -
    // Home's "Continue Watching" always came up empty. Separate, much
    // slower loop than the position-poll above - no need to hit the DB
    // every 500ms.
    // WatchTogetherForegroundService is the sole bookmark writer for a live
    // session (docs/tasks/watch-together-state-and-ux.md §5: "Never: screen
    // onProgress and FGS for the same session") - this loop only runs for
    // genuine solo playback, otherwise both would write the same DB row.
    if (currentSession == null) {
        LaunchedEffect(currentPlayer) {
            while (true) {
                delay(PROGRESS_SAVE_MS)
                val durationMs = runCatching { currentPlayer.durationMs }.getOrNull() ?: break
                val positionMs = runCatching { currentPlayer.positionMs }.getOrNull() ?: break
                if (durationMs > 0) onProgress(positionMs, durationMs)
            }
        }
    }

    var eventText by remember { mutableStateOf<String?>(null) }
    if (currentSession != null) {
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
                    WatchTogetherSession.WtEvent.HostLost -> {
                        // Demote to a local solo player instead of dying
                        // outright - the guest's own copy of the file is
                        // unaffected by the host disappearing, only the
                        // sync is gone. Dropping `session` to null makes
                        // every control below fall back to `currentPlayer`
                        // directly (same branches already used for plain
                        // solo playback), and removing it from the manager
                        // clears it from the Listing/foreground-service pool.
                        com.mofy.app.watchtogether.WatchTogetherSessionManager.remove(currentSession.roomKey)
                        session = null
                        "Host ended the session - continuing solo"
                    }
                }
            }
        }
        LaunchedEffect(eventText) {
            if (eventText != null) {
                delay(EVENT_TOAST_MS)
                eventText = null
            }
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
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
                Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            if (eventText != null) {
                Box(modifier = Modifier.width(12.dp))
                Text(
                    eventText.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        // An attached external subtitle slave becomes available in the
        // track list but libVLC does NOT auto-select it as active - without
        // this, a single linked subtitle (no second one, so the picker
        // below never even shows) would silently never render.
        val tracks = currentPlayer.subtitleTracks()
        val realTracks = tracks.filter { it.first != -1 }
        LaunchedEffect(realTracks) {
            if (currentSubtitleTrack == null && realTracks.isNotEmpty()) {
                val firstId = realTracks.first().first
                currentPlayer.setSubtitleTrack(firstId)
                currentSubtitleTrack = firstId
            }
        }

        // Top-right control cluster: orientation toggle, aspect-ratio
        // cycle, subtitle picker. Local-only, never synced across a Watch
        // Together room - each device picks its own fit/rotation.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(contentPadding)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable {
                        activity?.let {
                            it.requestedOrientation = if (
                                it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            ) {
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("⤢", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { aspectMenuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (videoScale) {
                            VideoScale.FIT -> "FIT"
                            VideoScale.FILL -> "FILL"
                            VideoScale.ORIGINAL -> "1:1"
                            VideoScale.RATIO_16_9 -> "16:9"
                            VideoScale.RATIO_4_3 -> "4:3"
                            VideoScale.RATIO_IMAX_143 -> "1.43"
                            VideoScale.RATIO_IMAX_190 -> "1.90"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                DropdownMenu(
                    expanded = aspectMenuOpen,
                    onDismissRequest = { aspectMenuOpen = false },
                ) {
                    VideoScale.entries.forEach { scale ->
                        DropdownMenuItem(
                            text = { Text(scale.label) },
                            onClick = {
                                videoScale = scale
                                currentPlayer.setVideoScale(scale)
                                aspectMenuOpen = false
                            },
                        )
                    }
                }
            }
            // Subtitle track picker, shown whenever libVLC reports any
            // track at all (its own "Disable" entry plus any real ones).
            if (tracks.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { subtitleMenuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CC", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(
                    expanded = subtitleMenuOpen,
                    onDismissRequest = { subtitleMenuOpen = false },
                ) {
                    tracks.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                currentPlayer.setSubtitleTrack(id.takeIf { it != -1 })
                                currentSubtitleTrack = id.takeIf { it != -1 }
                                subtitleMenuOpen = false
                            },
                        )
                    }
                }
            }
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
                    .clickable {
                        val target = (uiPositionMs - SEEK_NUDGE_MS).coerceAtLeast(0L)
                        if (currentSession != null) currentSession.localSeek(target) else currentPlayer.seekTo(target)
                    },
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
                    .clickable {
                        when {
                            currentSession != null && uiIsPlaying -> currentSession.localPause()
                            currentSession != null -> currentSession.localPlay()
                            uiIsPlaying -> currentPlayer.pause()
                            else -> currentPlayer.play()
                        }
                    },
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
                    .clickable {
                        val target = uiPositionMs + SEEK_NUDGE_MS
                        if (currentSession != null) currentSession.localSeek(target) else currentPlayer.seekTo(target)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White.copy(alpha = 0.85f))
            }
        }

        // Watch Together only: people icon with a live participant-count
        // badge, bottom-right. Replaces the old always-visible "Watching
        // with Priya +1" pill - presence is now a tap-to-check detail, and
        // join/leave/pause/etc. surface as the transient toast up top
        // instead of a persistent label.
        if (currentSession != null) {
            val state by currentSession.state.collectAsState()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(contentPadding)
                    .padding(end = 16.dp, bottom = 88.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { peopleMenuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.People, contentDescription = "Watching together", tint = Color.White)
                    if (state.participants.size > 1) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(16.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${state.participants.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }
                }
                DropdownMenu(
                    expanded = peopleMenuOpen,
                    onDismissRequest = { peopleMenuOpen = false },
                ) {
                    if (onInvite != null) {
                        DropdownMenuItem(
                            text = { Text("Invite") },
                            onClick = {
                                peopleMenuOpen = false
                                onInvite()
                            },
                        )
                    }
                    state.participants.forEach { participant ->
                        DropdownMenuItem(
                            text = {
                                ParticipantRow(
                                    participant = participant,
                                    isLocal = participant.id == state.localParticipantId,
                                )
                            },
                            onClick = {},
                        )
                    }
                }
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
                    onFractionChange = { fraction ->
                        isDraggingSeek = true
                        uiPositionMs = (fraction * durationMs).toLong()
                    },
                    onFractionChangeFinished = {
                        isDraggingSeek = false
                        if (currentSession != null) currentSession.localSeek(uiPositionMs) else currentPlayer.seekTo(uiPositionMs)
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}
