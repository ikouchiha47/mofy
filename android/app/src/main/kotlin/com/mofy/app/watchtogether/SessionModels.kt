package com.mofy.app.watchtogether

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class Role { HOST, GUEST }

@Serializable
data class Participant(
    val id: String,
    val displayName: String,
    val role: Role,
)

data class SessionState(
    val roomKey: String,
    val itemHash: String,
    val role: Role,
    val localParticipantId: String,
    val participants: List<Participant>,
    val positionMs: Long,
    val isPlaying: Boolean,
)

/**
 * A 6-character human-typeable room code. 32-symbol alphabet,
 * log2(32^6) ~= 30 bits of entropy — acceptable for short-lived personal
 * rooms (ADR 0006, mockup shows "7F · K9 · Q2").
 */
object RoomKey {
    const val LENGTH = 6
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // no 0/O/1/I

    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    fun isValid(raw: String): Boolean {
        val key = normalize(raw)
        return key.length == LENGTH && key.all { it in ALPHABET }
    }

    fun normalize(raw: String): String =
        raw.uppercase().filter { it in ALPHABET }
}

object SessionLimits {
    const val MAX_PARTICIPANTS = 10  // host + 9 guests
}