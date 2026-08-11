package com.mofy.app.watchtogether.protocol

import com.mofy.app.watchtogether.Participant
import com.mofy.app.watchtogether.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WtMessageCodecTest {

    private val guest = Participant(
        id = "guest-1",
        displayName = "Priya",
        role = Role.GUEST,
    )

    @Test
    fun `round-trips every variant`() {
        val messages = listOf(
            WtMessage.Hello(roomKey = "7FK9Q2", displayName = "Alex"),
            WtMessage.Join(roomKey = "7FK9Q2", displayName = "Priya", itemHash = "a1b2"),
            WtMessage.JoinAck(
                participantId = "guest-1",
                participants = listOf(guest),
                positionMs = 42_000,
                isPlaying = true,
            ),
            WtMessage.ParticipantEvent(
                op = WtMessage.ParticipantEvent.Op.JOINED,
                participant = guest,
            ),
            WtMessage.Play(positionMs = 1000, by = "guest-1"),
            WtMessage.Pause(positionMs = 1234, by = "host-1"),
            WtMessage.Seek(positionMs = 55_555, by = "guest-2"),
            WtMessage.Position(positionMs = 77_000, isPlaying = true, ts = 123456L),
            WtMessage.Pref(subtitleTrack = 1, audioTrack = null),
            WtMessage.Error(reason = "room_full"),
            WtMessage.Bye(reason = "host ended"),
            WtMessage.Bye(),
        )

        messages.forEach { original ->
            val decoded = WtMessageCodec.decode(WtMessageCodec.encode(original))
            assertEquals(original, decoded, "mismatch for ${original::class.simpleName}")
        }
    }

    @Test
    fun `golden seek JSON decodes to expected fields`() {
        val decoded = WtMessageCodec.decode("""{"type":"seek","positionMs":1000,"by":"abc"}""")

        assertEquals(WtMessage.Seek(positionMs = 1000, by = "abc"), decoded)
    }

    @Test
    fun `unknown type throws`() {
        assertThrows(Exception::class.java) {
            WtMessageCodec.decode("""{"type":"nope","x":1}""")
        }
    }

    @Test
    fun `malformed JSON throws`() {
        assertThrows(Exception::class.java) {
            WtMessageCodec.decode("not json at all")
        }
    }
}