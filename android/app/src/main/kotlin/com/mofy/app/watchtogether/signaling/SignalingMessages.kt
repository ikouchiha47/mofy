package com.mofy.app.watchtogether.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface SignalingWireMessage {
    @Serializable
    @SerialName("hello-sig")
    data class HelloSig(val roomKey: String, val peerId: String) : SignalingWireMessage

    @Serializable
    @SerialName("signal")
    data class Signal(
        val from: String,
        val to: String,
        val payload: String,
    ) : SignalingWireMessage

    @Serializable
    @SerialName("error")
    data class Error(val reason: String) : SignalingWireMessage
}

object SignalingCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(msg: SignalingWireMessage): String = json.encodeToString(msg)

    fun decode(raw: String): SignalingWireMessage = json.decodeFromString(raw)
}
