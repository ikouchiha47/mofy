package com.mofy.app.watchtogether.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for [WtMessage] over the WebRTC DataChannel (ADR 0006).
 * Discriminator field is "type" with the ADR's exact type values.
 */
object WtMessageCodec {

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(msg: WtMessage): String = json.encodeToString(msg)

    /** Throws on unknown type or malformed JSON (typically SerializationException). */
    fun decode(jsonString: String): WtMessage = json.decodeFromString(jsonString)
}