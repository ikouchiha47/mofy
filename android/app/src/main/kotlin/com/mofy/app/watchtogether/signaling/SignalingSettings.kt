package com.mofy.app.watchtogether.signaling

/**
 * Runtime config for Watch Together signaling bootstrap.
 *
 * Sourced from `.env` / `.env.prod` key `WT_SIGNALING_URL` →
 * `BuildConfig.WT_SIGNALING_URL` (see `android/app/build.gradle.kts`), applied
 * at app start via [applyBuildConfig].
 *
 * - [relayBaseUrl] null/blank → host embeds [EmbeddedSignalingServer] (LAN /
 *   local experiment). Guests use the `sig=` URL from the deep link / QR.
 * - non-null → host and guests both connect as clients to that relay.
 *   Examples in `.env`:
 *     WT_SIGNALING_URL=ws://192.168.1.20:8787
 *     WT_SIGNALING_URL=wss://mofy-sig.fly.dev
 *
 * Path `/wt/{roomKey}` is always appended by [urlForRoom].
 */
object SignalingSettings {
    @Volatile
    var relayBaseUrl: String? = null

    /** Call once from Application.onCreate with BuildConfig.WT_SIGNALING_URL. */
    fun applyBuildConfig(urlFromBuildConfig: String) {
        relayBaseUrl = urlFromBuildConfig.trim().takeIf { it.isNotEmpty() }
    }

    fun urlForRoom(roomKey: String, baseUrl: String? = relayBaseUrl): String? {
        val base = baseUrl?.trimEnd('/') ?: return null
        return "$base/wt/$roomKey"
    }
}
