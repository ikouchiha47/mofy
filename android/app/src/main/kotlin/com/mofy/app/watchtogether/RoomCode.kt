package com.mofy.app.watchtogether

/**
 * Display formatting and deep-link/QR payload handling for room keys
 * (ADR 0006). Pure string operations only — no android.net.Uri so JVM unit
 * tests stay on the desktop build.
 */
object RoomCode {
    private const val SCHEME = "mofy://"
    private const val PATH_PREFIX = "wt/"
    private const val QUERY_SIG = "sig="

    private const val GROUP_LEN = 2
    private const val SEPARATOR = " · "

    /** "7FK9Q2" → "7F · K9 · Q2". Only works for exactly 6 chars; passthrough otherwise. */
    fun formatForDisplay(roomKey: String): String {
        if (roomKey.length != RoomKey.LENGTH) return roomKey
        return listOf(
            roomKey.substring(0, GROUP_LEN),
            roomKey.substring(GROUP_LEN, GROUP_LEN * 2),
            roomKey.substring(GROUP_LEN * 2),
        ).joinToString(SEPARATOR)
    }

    /**
     * Parse user input / QR payload → roomKey or null. Accepts the grouped
     * display form ("7F · K9 · Q2"), with any separators stripped, plus the
     * raw 6-char key. Null for empty/invalid input.
     */
    fun parseUserInput(raw: String): String? {
        val cleaned = raw
            .trim()
            .filter { it.isLetterOrDigit() }
            .uppercase()
        if (cleaned.length != RoomKey.LENGTH) return null
        return if (cleaned.all { it in RoomKey.ALPHABET }) cleaned else null
    }

    /**
     * Deep link / QR payload v1: mofy://wt/{roomKey}.
     * Optional query for LAN signaling bootstrap: mofy://wt/{roomKey}?sig=ws://{host}:{port}/
     */
    fun toDeepLink(roomKey: String, signalingUrl: String? = null): String {
        val base = SCHEME + PATH_PREFIX + roomKey
        return if (signalingUrl == null) base else "$base?$QUERY_SIG${signalingUrl}"
    }

    /** @return null if uri is not a usable mofy://wt/ deep link. */
    fun parseDeepLink(uri: String): Parsed? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(SCHEME)) return null
        val rest = trimmed.removePrefix(SCHEME)
        if (!rest.startsWith(PATH_PREFIX)) return null

        val pathAndQuery = rest.removePrefix(PATH_PREFIX)
        val (pathPart, queryPart) = pathAndQuery.split("?", limit = 2).let {
            it[0] to (if (it.size > 1) it[1] else null)
        }

        val roomKey = pathPart.uppercase().filter { it in RoomKey.ALPHABET }
        if (roomKey.length != RoomKey.LENGTH) return null

        val signalingUrl = queryPart
            ?.split("&")
            ?.firstOrNull { it.startsWith(QUERY_SIG) }
            ?.removePrefix(QUERY_SIG)
            ?.let { it.takeUnless(String::isBlank) }

        return Parsed(roomKey, signalingUrl)
    }

    data class Parsed(val roomKey: String, val signalingUrl: String?)
}