package com.mofy.app.data.library

/**
 * Best-effort title guess from a release-style filename - regex heuristics,
 * not NLP. This is a solved problem (Radarr/Sonarr/Filebot all do the same
 * thing): scan tokens left to right and stop at the first one that looks
 * like resolution/source/codec/audio/year/episode noise, since that marker
 * is where the actual title ends in almost every release naming convention.
 * Always a guess, never trusted blindly - see ConfirmMatchScreen's
 * autoSearch=false editable-title flow for imports.
 */
private val NOISE_MARKERS = setOf(
    "480p", "720p", "1080p", "2160p", "4k", "uhd",
    "bluray", "blu-ray", "brrip", "bdrip", "webrip", "web-dl", "webdl", "web",
    "hdtv", "dvdrip", "hdrip", "camrip", "cam", "hdcam", "dvdscr",
    "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx",
    "aac", "ac3", "dts", "dd5", "truehd", "atmos",
    "repack", "proper", "extended", "unrated", "remastered", "imax",
    "multi", "dual", "dubbed", "yts", "yify", "rarbg", "eztv",
)

private val YEAR_PATTERN = Regex("^(19|20)\\d{2}$")
private val EPISODE_PATTERN = Regex("^s\\d{1,2}(e\\d{1,2})?$", RegexOption.IGNORE_CASE)

fun guessTitleFromFileName(rawName: String): String {
    val withoutExt = rawName.substringBeforeLast('.', rawName)
    val normalized = withoutExt.replace(Regex("[._-]"), " ")
    val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }

    val titleTokens = mutableListOf<String>()
    for (token in tokens) {
        val bare = token.trim('[', ']', '(', ')')
        val lower = bare.lowercase()
        if (lower in NOISE_MARKERS) break
        if (YEAR_PATTERN.matches(bare)) break
        if (EPISODE_PATTERN.matches(bare)) break
        titleTokens.add(token)
    }

    val guess = titleTokens.joinToString(" ").trim()
    return guess.ifBlank { withoutExt.trim() }
}
