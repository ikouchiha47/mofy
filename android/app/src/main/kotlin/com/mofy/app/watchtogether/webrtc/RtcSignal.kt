package com.mofy.app.watchtogether.webrtc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Opaque signaling payloads carried on [com.mofy.app.watchtogether.transport.SignalingChannel]. */
@Serializable
sealed interface RtcSignal {
    /** Guest → host: please create an offer for me. */
    @Serializable
    @SerialName("join-rtc")
    data object JoinRtc : RtcSignal

    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : RtcSignal

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : RtcSignal

    @Serializable
    @SerialName("ice")
    data class Ice(
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0,
    ) : RtcSignal
}

object RtcSignalCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    fun encode(signal: RtcSignal): String = json.encodeToString(signal)

    fun decode(raw: String): RtcSignal = json.decodeFromString(raw)
}
