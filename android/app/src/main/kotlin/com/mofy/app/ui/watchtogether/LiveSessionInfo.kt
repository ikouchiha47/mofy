package com.mofy.app.ui.watchtogether

import com.mofy.app.watchtogether.Participant
import com.mofy.app.watchtogether.Role
import com.mofy.app.watchtogether.SessionState

data class LiveSessionInfo(
    val watchingWithLabel: String,
    val statusLabel: String,
)

fun SessionState.toLiveSessionInfo(): LiveSessionInfo {
    val others = participants.filter { it.id != localParticipantId }
    val watchingWithLabel = when {
        others.isEmpty() -> "Watching solo"
        others.size == 1 -> "Watching with ${others[0].displayName}"
        else -> "Watching with ${others[0].displayName} +${others.size - 1}"
    }
    val statusLabel = "${if (isPlaying) "live" else "paused"} · ${formatPositionMs(positionMs)}"
    return LiveSessionInfo(watchingWithLabel, statusLabel)
}

private fun formatPositionMs(positionMs: Long): String {
    val totalSeconds = positionMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

internal fun previewSessionState(): SessionState = SessionState(
    roomKey = "7FK9Q2",
    itemHash = "preview",
    role = Role.HOST,
    localParticipantId = "host-1",
    participants = listOf(
        Participant(id = "host-1", displayName = "You", role = Role.HOST),
        Participant(id = "guest-1", displayName = "Priya", role = Role.GUEST),
        Participant(id = "guest-2", displayName = "Arun", role = Role.GUEST),
    ),
    positionMs = 42 * 60_000L + 10_000L,
    isPlaying = true,
)
