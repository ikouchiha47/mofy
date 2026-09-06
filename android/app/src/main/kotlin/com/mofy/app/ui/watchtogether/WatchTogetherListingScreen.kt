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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mofy.app.ui.icons.AppIcons
import com.mofy.app.ui.theme.MofyAccent
import com.mofy.app.ui.theme.MofyGood
import com.mofy.app.ui.theme.MofySurface
import com.mofy.app.ui.theme.MofySurfaceVariant
import com.mofy.app.ui.theme.MofyText
import com.mofy.app.ui.theme.MofyTextDim
import com.mofy.app.watchtogether.Role

/** One row of [WatchTogetherListingScreen] - a session this device is currently in, host or guest. */
data class ListingRow(
    val roomKey: String,
    val title: String,
    val role: Role,
    val participantCount: Int,
    val isPlaying: Boolean,
)

/**
 * Hub for Watch Together (docs/tasks/watch-together-redesign.md) - lists
 * every session this device currently participates in (host or guest, up
 * to SessionLimits.MAX_CONCURRENT_SESSIONS) plus "Join a Room". Replaces
 * the old behavior where Home's 👥 icon opened the guest-join sheet
 * directly with nothing in front of it.
 *
 * Leave is the only teardown control - no separate host-only "End" (see
 * the redesign doc's Decisions section). Leaving as host is not gentle
 * (kills the room for connected guests), so it's the one action here that
 * asks for confirmation first; leaving as guest doesn't affect anyone else
 * and fires immediately.
 */
@Composable
fun WatchTogetherListingScreen(
    contentPadding: PaddingValues,
    rows: List<ListingRow>,
    canJoinMore: Boolean,
    onOpenRow: (roomKey: String) -> Unit,
    onLeave: (roomKey: String) -> Unit,
    onJoinRoom: () -> Unit,
) {
    var pendingHostLeaveRoomKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
    ) {
        Button(
            onClick = onJoinRoom,
            enabled = canJoinMore,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MofyAccent),
        ) {
            Text(if (canJoinMore) "Join a Room" else "Join a Room (limit reached)")
        }

        Spacer(Modifier.height(16.dp))

        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No active sessions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MofyText,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Start one from a title's Detail screen, or join with a code above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MofyTextDim,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.roomKey }) { row ->
                    ListingRowCard(
                        row = row,
                        onOpen = { onOpenRow(row.roomKey) },
                        onLeave = {
                            if (row.role == Role.HOST) {
                                pendingHostLeaveRoomKey = row.roomKey
                            } else {
                                onLeave(row.roomKey)
                            }
                        },
                    )
                }
            }
        }
    }

    val hostLeaveTarget = pendingHostLeaveRoomKey
    if (hostLeaveTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingHostLeaveRoomKey = null },
            title = { Text("End party for everyone?") },
            text = { Text("Guests will continue watching alone.") },
            confirmButton = {
                TextButton(onClick = {
                    onLeave(hostLeaveTarget)
                    pendingHostLeaveRoomKey = null
                }) {
                    Text("End party")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHostLeaveRoomKey = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ListingRowCard(
    row: ListingRow,
    onOpen: () -> Unit,
    onLeave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MofySurfaceVariant)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (row.role == Role.HOST) MofyAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.People,
                contentDescription = null,
                tint = if (row.role == Role.HOST) MofyAccent else MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MofyText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.role == Role.HOST) "Host" else "Guest",
                    style = MaterialTheme.typography.labelSmall,
                    color = MofyTextDim,
                )
                Text(" · ", style = MaterialTheme.typography.labelSmall, color = MofyTextDim)
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (row.isPlaying) MofyGood else MofyTextDim),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        row.isPlaying -> "Playing · ${row.participantCount} watching"
                        row.participantCount <= 1 -> "Waiting for someone to join…"
                        else -> "${row.participantCount} watching · ready to start"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MofyTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onLeave) {
            Icon(AppIcons.Close, contentDescription = "Leave", tint = MofyTextDim)
        }
    }
}
