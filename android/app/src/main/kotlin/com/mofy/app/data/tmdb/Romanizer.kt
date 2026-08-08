package com.mofy.app.data.tmdb

import android.icu.text.Transliterator

/**
 * Converts non-Latin script (Chinese, Japanese, Korean, Cyrillic, etc.) to
 * a plain-ASCII romanization - e.g. 火遮眼 -> "Huo Zhe Yan". Uses Android's
 * bundled ICU library (API 24+), not a translation - "Any-Latin" picks the
 * right script-specific transliteration rules automatically, "Latin-ASCII"
 * then strips tone marks/diacritics down to plain ASCII. Fully on-device,
 * no network call, no API key - this is exactly the gap between TMDB's
 * `original_title` (native script) and what a release group's own
 * filename usually romanizes it to.
 */
private val transliterator: Transliterator by lazy {
    Transliterator.getInstance("Any-Latin; Latin-ASCII")
}

fun romanize(text: String): String = transliterator.transliterate(text)

/** True if the string contains characters outside basic Latin - i.e. worth romanizing. */
fun isNonLatinScript(text: String): Boolean = text.any { it.code > 0x024F }
