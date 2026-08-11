package com.mofy.app.watchtogether

import com.mofy.app.data.library.LibraryItem
import java.security.MessageDigest
import java.util.Locale

/**
 * Stable identity hash for a library item that two independently-populated
 * libraries agree on (ADR 0006 / Phase 13: same movie on both devices must
 * produce the same value).
 *
 * Prefers the TMDB identity when present; falls back to a normalized-title
 * hash for manual/DISCOVERED entries that have no [tmdbId]. Output is a
 * fixed-length 64-bit hex prefix, so it never leaks the raw id or length of
 * the title.
 */
object ItemHash {

    /**
     * [tmdbId] + [mediaType] identify the item when both are present. Any
     * malformed title (empty, non-ASCII-stripped to nothing) still hashes
     * deterministically rather than throwing.
     */
    fun of(tmdbId: Int?, mediaType: String?, title: String): String {
        val input = if (tmdbId != null && !mediaType.isNullOrBlank()) {
            "tmdb:${mediaType.lowercase(Locale.ROOT)}:$tmdbId"
        } else {
            "title:${normalizeTitle(title)}"
        }
        return sha256Hex(input).substring(0, HASH_PREFIX_HEX_CHARS)
    }

    fun of(item: LibraryItem): String = of(item.tmdbId, item.mediaType, item.title)

    /**
     * Lowercase (Locale.ROOT), strip anything that is not an ASCII letter or
     * digit, trim. Names are identical across devices even when typed
     * differently. Note: this intentionally removes separators such as "·" so
     * a title can never collide with the "tmdb:" branch's prefix.
     */
    internal fun normalizeTitle(title: String): String =
        title.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val HASH_PREFIX_HEX_CHARS = 16
}