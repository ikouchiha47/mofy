package com.mofy.app.watchtogether.webrtc

/**
 * WebRTC constants for Watch Together (ADR 0006). STUN only — no TURN in v1.
 */
object WtWebRtcConfig {
    const val CONTROL_CHANNEL_LABEL = "wt-control"

    val STUN_URLS = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
    )
}
