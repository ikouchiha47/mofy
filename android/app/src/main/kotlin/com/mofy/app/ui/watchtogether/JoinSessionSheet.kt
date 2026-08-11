package com.mofy.app.ui.watchtogether

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mofy.app.data.library.LibraryItem
import com.mofy.app.playback.PlayerController
import com.mofy.app.ui.theme.MofyAccent
import com.mofy.app.ui.theme.MofyBorder
import com.mofy.app.ui.theme.MofySurface
import com.mofy.app.ui.theme.MofySurfaceVariant
import com.mofy.app.ui.theme.MofyText
import com.mofy.app.ui.theme.MofyTextDim
import com.mofy.app.watchtogether.RoomCode
import com.mofy.app.watchtogether.RoomKey
import com.mofy.app.watchtogether.WatchTogetherSession
import com.mofy.app.watchtogether.signaling.SignalingSettings

private sealed interface JoinPhase {
    data object Entering : JoinPhase
    data class Connecting(val session: WatchTogetherSession) : JoinPhase
    data class Failed(val reason: String) : JoinPhase
}

/**
 * Mockup 2b/2c/2d. Digit entry with auto-advance, then a connect attempt
 * against [WatchTogetherSession.guest] with inline error/loading states -
 * no navigation, that's the caller's job via [onJoined].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinSessionSheet(
    libraryItem: LibraryItem,
    itemHash: String,
    player: PlayerController,
    appContext: Context,
    displayName: String,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onJoined: (WatchTogetherSession) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var digits by remember { mutableStateOf(List(RoomKey.LENGTH) { "" }) }
    var phase by remember { mutableStateOf<JoinPhase>(JoinPhase.Entering) }
    val focusRequesters = remember { List(RoomKey.LENGTH) { FocusRequester() } }
    val roomCode = digits.joinToString("")

    val currentPhase = phase
    if (currentPhase is JoinPhase.Connecting) {
        LaunchedEffect(currentPhase.session) {
            currentPhase.session.events.collect { event ->
                when (event) {
                    is WatchTogetherSession.WtEvent.Joined -> onJoined(currentPhase.session)
                    is WatchTogetherSession.WtEvent.Error -> phase = JoinPhase.Failed(event.reason)
                    else -> Unit
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MofySurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            when (val current = phase) {
                is JoinPhase.Entering -> EnteringContent(
                    digits = digits,
                    focusRequesters = focusRequesters,
                    onDigitsChange = { digits = it },
                    onJoin = {
                        val parsed = RoomCode.parseUserInput(roomCode)
                        val signalingUrl = parsed?.let { SignalingSettings.urlForRoom(it) }
                        when {
                            parsed == null -> phase = JoinPhase.Failed("Invalid room code")
                            signalingUrl == null -> phase = JoinPhase.Failed(
                                "No relay configured - use Scan QR or a share link instead",
                            )
                            else -> {
                                val session = WatchTogetherSession.guest(
                                    roomKey = parsed,
                                    signalingUrl = signalingUrl,
                                    itemHash = itemHash,
                                    displayName = displayName,
                                    player = player,
                                    appContext = appContext,
                                )
                                phase = JoinPhase.Connecting(session)
                            }
                        }
                    },
                    onScanQr = onScanQr,
                )
                is JoinPhase.Connecting -> ConnectingContent(libraryItem = libraryItem, roomCode = roomCode)
                is JoinPhase.Failed -> FailedContent(
                    reason = current.reason,
                    libraryItem = libraryItem,
                    roomCode = roomCode,
                    onRetry = { phase = JoinPhase.Entering },
                )
            }
        }
    }
}

@Composable
private fun EnteringContent(
    digits: List<String>,
    focusRequesters: List<FocusRequester>,
    onDigitsChange: (List<String>) -> Unit,
    onJoin: () -> Unit,
    onScanQr: () -> Unit,
) {
    Text("Join Watch Together", style = MaterialTheme.typography.titleLarge, color = MofyText)
    Spacer(Modifier.height(4.dp))
    Text(
        "Enter the 6-character code you were sent",
        style = MaterialTheme.typography.bodyMedium,
        color = MofyTextDim,
    )
    Spacer(Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        digits.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { input ->
                    val char = input.uppercase().filter { it in RoomKey.ALPHABET }.take(1)
                    if (char.isEmpty() && input.isEmpty()) {
                        onDigitsChange(digits.toMutableList().also { it[index] = "" })
                    } else if (char.isNotEmpty()) {
                        onDigitsChange(digits.toMutableList().also { it[index] = char })
                        if (index < focusRequesters.lastIndex) {
                            focusRequesters[index + 1].requestFocus()
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.8f)
                    .focusRequester(focusRequesters[index]),
                textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MofySurfaceVariant,
                    unfocusedContainerColor = MofySurfaceVariant,
                    focusedBorderColor = MofyAccent,
                    unfocusedBorderColor = MofyBorder,
                    focusedTextColor = MofyText,
                    unfocusedTextColor = MofyText,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onJoin,
        enabled = digits.all { it.isNotEmpty() },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = MofyAccent),
    ) {
        Text("Join")
    }

    Spacer(Modifier.height(16.dp))
    Text("or", style = MaterialTheme.typography.bodySmall, color = MofyTextDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onScanQr,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    ) {
        Text("Scan QR Code")
    }
}

@Composable
private fun ConnectingContent(libraryItem: LibraryItem, roomCode: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MofyAccent)
        Spacer(Modifier.height(16.dp))
        Text("Connecting to host", style = MaterialTheme.typography.titleMedium, color = MofyText)
        Spacer(Modifier.height(4.dp))
        Text(
            "Looking up room · matching title in your library",
            style = MaterialTheme.typography.bodySmall,
            color = MofyTextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        MatchCard(libraryItem = libraryItem, subtitle = "Host is watching this")
    }
}

@Composable
private fun FailedContent(
    reason: String,
    libraryItem: LibraryItem,
    roomCode: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't join", style = MaterialTheme.typography.titleLarge, color = MofyText)
        Spacer(Modifier.height(4.dp))
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MofyTextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        MatchCard(libraryItem = libraryItem, subtitle = "${libraryItem.year ?: ""}".trim())
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = MofySurfaceVariant),
        ) {
            Text("Try again")
        }
    }
}

@Composable
private fun MatchCard(libraryItem: LibraryItem, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MofySurfaceVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MofyBorder, MaterialTheme.shapes.small),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(libraryItem.title, style = MaterialTheme.typography.titleSmall, color = MofyText)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MofyTextDim)
            }
        }
    }
}
