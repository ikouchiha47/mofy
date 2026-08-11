package com.mofy.app.ui.watchtogether

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.WatchTogetherSession

@Composable
fun GuestLobbyScreen(
    contentPadding: PaddingValues,
    session: WatchTogetherSession,
    onSessionStarted: () -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val hostName = state.participants.firstOrNull { it.role == Role.HOST }?.displayName ?: "the host"

    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is WatchTogetherSession.WtEvent.ParticipantJoined ->
                    Toast.makeText(context, "${event.participant.displayName} joined", Toast.LENGTH_SHORT).show()
                is WatchTogetherSession.WtEvent.ParticipantLeft ->
                    Toast.makeText(context, "A guest left", Toast.LENGTH_SHORT).show()
                is WatchTogetherSession.WtEvent.Error ->
                    Toast.makeText(context, event.reason, Toast.LENGTH_SHORT).show()
                else -> Unit
            }
        }
    }

    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) onSessionStarted()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(contentPadding).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Box(
                modifier = Modifier
                    .padding(top = 28.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                "You're in",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Connected to $hostName's room. Waiting for host to start playback.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                state.participants.forEach { participant ->
                    ParticipantRow(
                        participant = participant,
                        isLocal = participant.id == state.localParticipantId,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(modifier = Modifier.padding(start = 6.dp))
                Text(
                    "Waiting for host to press play…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = onLeave,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Text("Leave session")
            }
        }
    }
}
