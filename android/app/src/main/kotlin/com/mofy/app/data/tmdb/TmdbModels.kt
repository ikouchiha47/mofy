package com.mofy.app.data.tmdb

import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchResponse(
    val page: Int,
    val results: List<TmdbResultDto>,
    val total_pages: Int,
    val total_results: Int,
)

@Serializable
data class TmdbResultDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val original_title: String? = null,
    val original_name: String? = null,
    val overview: String = "",
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val genre_ids: List<Int> = emptyList(),
    val vote_average: Double = 0.0,
)

@Serializable
data class TmdbFindResponse(
    val movie_results: List<TmdbResultDto> = emptyList(),
    val tv_results: List<TmdbResultDto> = emptyList(),
) {
    val posterPath: String?
        get() = (movie_results + tv_results).firstOrNull()?.poster_path
}

@Serializable
data class TmdbExternalIdsDto(
    val imdb_id: String? = null,
)

enum class MediaType { MOVIE, TV }

const val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w342"

data class MediaResult(
    val id: Int,
    val title: String,
    // TMDB's own-language title (e.g. Chinese characters for a Chinese
    // film) - not romanized, and often not what a release group used in
    // their torrent's filename either, but worth surfacing since `title`
    // alone (the localized/English display name) can search-miss on sites
    // indexed by a different name entirely. null when identical to title.
    val originalTitle: String?,
    // On-device romanization of originalTitle (see Romanizer.kt) - this is
    // usually the closest match to what a release group's filename used
    // (e.g. "Huo Zhe Yan"), not originalTitle itself (native script) or
    // title (can be a translated display name, not a transliteration).
    val romanizedOriginalTitle: String?,
    val overview: String,
    val posterPath: String?,
    val year: String?,
    val genreIds: List<Int>,
    val voteAverage: Double,
    val mediaType: MediaType,
) {
    val posterUrl: String?
        get() = posterPath?.let { "$TMDB_IMAGE_BASE_URL$it" }
}

fun TmdbResultDto.toMediaResult(mediaType: MediaType): MediaResult {
    val displayTitle = title ?: name ?: ""
    val original = (original_title ?: original_name)?.takeIf { it.isNotBlank() && it != displayTitle }
    val romanized = original?.takeIf { isNonLatinScript(it) }?.let { romanize(it) }
    return MediaResult(
        id = id,
        title = displayTitle,
        originalTitle = original,
        romanizedOriginalTitle = romanized,
        overview = overview,
        posterPath = poster_path,
        year = (release_date ?: first_air_date)?.take(4),
        genreIds = genre_ids,
        voteAverage = vote_average,
        mediaType = mediaType,
    )
}
