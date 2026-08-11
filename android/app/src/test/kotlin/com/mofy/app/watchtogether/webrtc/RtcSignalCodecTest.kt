package com.mofy.app.watchtogether.webrtc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RtcSignalCodecTest {
    @Test
    fun `round-trips rtc signal variants`() {
        val messages = listOf(
            RtcSignal.JoinRtc,
            RtcSignal.Offer("v=0"),
            RtcSignal.Answer("v=0-answer"),
            RtcSignal.Ice(candidate = "cand", sdpMid = "0", sdpMLineIndex = 0),
        )
        messages.forEach { original ->
            assertEquals(original, RtcSignalCodec.decode(RtcSignalCodec.encode(original)))
        }
    }
}
