package com.mofy.app.ui.watchtogether

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mofy.app.watchtogether.Participant
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.RoomCode
import com.mofy.app.watchtogether.WatchTogetherSession

private const val QR_SIZE_PX = 480

@Composable
fun CreateRoomScreen(
    contentPadding: PaddingValues,
    session: WatchTogetherSession,
    onStartWatching: () -> Unit,
) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val qrBitmap = remember(session.deepLink) { qrCodeBitmap(session.deepLink, QR_SIZE_PX) }
    val shareLabel = RoomCode.formatForDisplay(session.roomKey)

    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is WatchTogetherSession.WtEvent.ParticipantJoined ->
                    Toast.makeText(context, "${event.participant.displayName} joined", Toast.LENGTH_SHORT).show()
                is WatchTogetherSession.WtEvent.Error ->
                    Toast.makeText(context, event.reason, Toast.LENGTH_SHORT).show()
                is WatchTogetherSession.WtEvent.ParticipantLeft ->
                    Toast.makeText(context, "A guest left", Toast.LENGTH_SHORT).show()
                else -> Unit
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(contentPadding).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(
                "Room code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                shareLabel,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = 4.dp),
            )

            Image(
                bitmap = qrBitmap,
                contentDescription = "QR code for room invite",
                modifier = Modifier
                    .padding(top = 20.dp)
                    .size(200.dp)
                    .clip(MaterialTheme.shapes.large),
            )

            WaitingRow(participantCount = state.participants.size, modifier = Modifier.padding(top = 20.dp))

            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                state.participants.forEach { participant ->
                    ParticipantRow(
                        participant = participant,
                        isLocal = participant.id == state.localParticipantId,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            Button(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join me: $shareLabel\n${session.deepLink}")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share invite"))
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Text("🔗 Share invite")
            }

            OutlinedButton(
                onClick = onStartWatching,
                enabled = state.participants.size >= 2,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Text("▶ Start watching")
            }
        }
    }
}

@Composable
private fun WaitingRow(participantCount: Int, modifier: Modifier = Modifier) {
    val ready = participantCount >= 2
    val label = if (ready) "$participantCount watching · ready to start" else "Waiting for someone to join…"
    val dotColor = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.padding(start = 6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ParticipantRow(participant: Participant, isLocal: Boolean, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (participant.role == Role.HOST) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                participant.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (participant.role == Role.HOST) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(modifier = Modifier.padding(start = 10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (isLocal) "You" else participant.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (participant.role == Role.HOST) "Host" else "Guest",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isLocal) {
            Text("you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
