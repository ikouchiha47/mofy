package com.mofy.app.watchtogether.protocol

import com.mofy.app.watchtogether.Participant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Watch Together room-lifetime messages (ADR 0006). Serialized as JSON with
 * discriminator field name "type"; values match the ADR surface exactly.
 */
@Serializable
sealed interface WtMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(val roomKey: String, val displayName: String) : WtMessage

    @Serializable
    @SerialName("join")
    data class Join(val roomKey: String, val displayName: String, val itemHash: String) : WtMessage

    @Serializable
    @SerialName("join-ack")
    data class JoinAck(
        val participantId: String,
        val participants: List<Participant>,
        val positionMs: Long,
        val isPlaying: Boolean,
    ) : WtMessage

    @Serializable
    @SerialName("participant")
    data class ParticipantEvent(val op: Op, val participant: Participant) : WtMessage {
        @Serializable
        enum class Op { JOINED, LEFT }
    }

    @Serializable
    @SerialName("play")
    data class Play(val positionMs: Long, val by: String) : WtMessage

    @Serializable
    @SerialName("pause")
    data class Pause(val positionMs: Long, val by: String) : WtMessage

    @Serializable
    @SerialName("seek")
    data class Seek(val positionMs: Long, val by: String) : WtMessage

    @Serializable
    @SerialName("position")
    data class Position(val positionMs: Long, val isPlaying: Boolean, val ts: Long) : WtMessage

    @Serializable
    @SerialName("pref")
    data class Pref(val subtitleTrack: Int? = null, val audioTrack: Int? = null) : WtMessage

    @Serializable
    @SerialName("error")
    data class Error(val reason: String) : WtMessage

    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String? = null) : WtMessage
}